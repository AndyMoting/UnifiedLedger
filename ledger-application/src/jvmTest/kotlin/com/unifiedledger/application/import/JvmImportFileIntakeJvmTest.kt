package com.unifiedledger.application.import

import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_INTAKE_MAX_ACCEPTED_RECORDS
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportFormatUnavailableReason
import com.unifiedledger.application.ImportIntakeBatchFailure
import com.unifiedledger.application.ImportIntakeCommitPort
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportIntakeSessionIdentity
import com.unifiedledger.application.ImportIntakeSnapshot
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.SPINE_NO_CHANGE_REASON_CODE
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.application.aggregateImportIntakeRecords
import com.unifiedledger.domain.LedgerId
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P7-04.B intake-orchestration tests (D-146 Q09, spec sections 3.2.1/4.2.3).
 *
 * Covers: the pick-session identity (one opaque handle per file pick, one requestId per
 * ordinal memoized across retries), the per-record intake with a shared inputRef and
 * distinct requestIds, the L2 accepted-record bound (typed carrying the actual count, zero
 * intake writes), the format matrix gate, the GB18030 runtime probe, the whole-batch
 * parser rejection mapping, and the empty header-only aggregation branch. All inputs are
 * anonymous synthetic CMB CSV bytes; the commit port is a recording double, so no
 * persistence is involved.
 */
class JvmImportFileIntakeJvmTest {
    private val ledgerId = LedgerId("ledger-p704-intake")

    /**
     * Fixed timestamp keeps ids deterministic, while a monotonic stream keeps every call
     * (and therefore every session handle / requestId) distinct across picks.
     */
    private val randomCounter = AtomicInteger(0)

    private fun generator(): UuidV7Generator =
        UuidV7Generator(
            randomBytes = { count -> ByteArray(count) { randomCounter.getAndIncrement().toByte() } },
            timestampMillis = { 1_780_000_000_000L },
        )

    private fun newSession(): ImportIntakeSessionIdentity = ImportIntakeSessionIdentity.forFilePick(generator())

    /** Recording commit port: mints ids for each dispatched snapshot and returns them. */
    private class RecordingIntakePort(
        private val mode: Mode = Mode.ACCEPT,
    ) : ImportIntakeCommitPort {
        enum class Mode { ACCEPT, NO_CHANGE }

        val snapshots = mutableListOf<ImportIntakeSnapshot>()

        override fun commitIntake(
            identity: ImportRequestIdentity,
            snapshot: ImportIntakeSnapshot,
            allocateIds: (requiredDuplicateIds: Int) -> ImportIntakeIds,
        ): ImportIntakeResult {
            snapshots += snapshot
            if (mode == Mode.NO_CHANGE) {
                return ImportIntakeResult.NoChange(emptyList(), null, SPINE_NO_CHANGE_REASON_CODE)
            }
            val ids = allocateIds(0)
            return ImportIntakeResult.Accepted(
                receipt =
                    ImportReceipt(identity.requestId, ids.sourceId, ids.evidenceId, ids.candidateId, null, null),
                returnedIds = emptyList(),
            )
        }
    }

    private class CountingIntakeIdSource : ImportIntakeIdSource {
        var calls = 0

        override fun next(requiredDuplicateIds: Int): ImportIntakeIds {
            calls++
            return ImportIntakeIds(
                ImportSourceId("source-$calls"),
                ImportEvidenceId("evidence-$calls"),
                ImportCandidateId("candidate-$calls"),
                ImportStatusHistoryId("status-$calls"),
            )
        }
    }

    private fun orchestrator(
        port: ImportIntakeCommitPort,
        idSource: ImportIntakeIdSource = CountingIntakeIdSource(),
        charsetSupported: (String) -> Boolean = { true },
    ) = JvmImportFileIntake(
        ledgerId = ledgerId,
        executeIntake = ExecuteImportIntake(port, idSource, ImportContentFingerprint()),
        candidateGeneratedAt = { "2026-09-01T00:00:00Z" },
        charsetSupported = charsetSupported,
    )

    // ---------------------------------------------------------------- CMB CSV fixtures

    private fun cmbCsv(dataRows: List<String>): ByteArray {
        val comment = (0..5).map { "\"SYN-CMB-META-$it\"" }
        val lines =
            comment +
                listOf("\"\"") +
                listOf("\"交易日期\",\"交易时间\",\"收入\",\"支出\",\"余额\",\"交易类型\",\"交易备注\"") +
                dataRows +
                listOf("\"\"")
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    private fun cmbRow(
        date: String = "20260901",
        time: String = "08:30:00",
        expense: String = "1.00",
        balance: String = "1000000.00",
    ): String =
        listOf("\t$date", "\t$time", "", expense, balance, "网联协议支付", "")
            .joinToString(",") { "\"$it\"" }

    private fun cmbInput(
        session: ImportIntakeSessionIdentity,
        rows: List<String>,
    ) = ImportFileIntakeInput(
        format = ImportFormatCapabilities.CMB_CSV.identifier,
        platform = ImportPlatformKind.DESKTOP,
        session = session,
        bytes = cmbCsv(rows),
    )

    // ---------------------------------------------------------------- session identity

    @Test
    fun sessionIdentityMintsOneHandlePerPickAndMemoizesRequestIdsPerOrdinal() {
        val session = newSession()
        assertTrue(session.inputRef.isNotBlank())
        // One opaque handle for the whole pick; the two ordinals get distinct memoized ids.
        val first = session.requestIdFor(0)
        val second = session.requestIdFor(1)
        assertNotEquals(first, second)
        assertEquals(first, session.requestIdFor(0))
        assertEquals(second, session.requestIdFor(1))

        val otherSession = newSession()
        assertNotEquals(session.inputRef, otherSession.inputRef)
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun acceptedRecordsShareTheSessionHandleAndCarryDistinctRequestIds() {
        val session = newSession()
        val port = RecordingIntakePort()
        val idSource = CountingIntakeIdSource()
        val outcome =
            orchestrator(port, idSource).intake(cmbInput(session, listOf(cmbRow(), cmbRow(date = "20260902"))))

        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(2, accepted.records.size)
        assertEquals(2, accepted.newCandidateIds.size)
        assertTrue(accepted.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED })
        assertEquals(2, idSource.calls)

        assertEquals(2, port.snapshots.size)
        assertTrue(port.snapshots.all { it.inputRef == session.inputRef })
        assertEquals(listOf(0, 1), port.snapshots.map { it.recordOrdinal })
        val requestIds = port.snapshots.map { it.identity.requestId }
        assertEquals(2, requestIds.toSet().size)
        assertEquals(setOf(ledgerId), port.snapshots.map { it.identity.ledgerId }.toSet())
    }

    @Test
    fun allNoChangeRedispatchAggregatesToNoChangeAll() {
        val session = newSession()
        val outcome =
            orchestrator(RecordingIntakePort(RecordingIntakePort.Mode.NO_CHANGE))
                .intake(cmbInput(session, listOf(cmbRow(), cmbRow(date = "20260902"))))

        val noChange = assertIs<ImportFileIntakeOutcome.NoChangeAll>(outcome)
        assertEquals(2, noChange.records.size)
        assertTrue(noChange.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_NO_CHANGE })
    }

    @Test
    fun headerOnlyFileWithZeroDispatchedRecordsIsAcceptedEmptyNotNoChangeAll() {
        // AB-BE-QUAL-06 (registered deviation over the frozen fold's empty-set gap): a
        // header-only pick dispatches nothing, so it aggregates to an empty Accepted —
        // never to NoChangeAll ("session-level equivalent re-dispatch" would misreport a
        // first pick that simply carried no data rows).
        val port = RecordingIntakePort()
        val outcome = orchestrator(port).intake(cmbInput(newSession(), emptyList()))

        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(0, accepted.records.size)
        assertEquals(0, accepted.newCandidateIds.size)
        assertEquals(0, port.snapshots.size)

        // The fold's empty branch pinned directly.
        val folded = assertIs<ImportFileIntakeOutcome.Accepted>(aggregateImportIntakeRecords(emptyList(), emptyList()))
        assertEquals(0, folded.records.size)
        assertEquals(0, folded.newCandidateIds.size)
    }

    @Test
    fun parserRejectedRowsAreSummarizedAsSafeLocationDigests() {
        val session = newSession()
        // An unknown 交易类型 token is a typed row rejection (zero intake, no renumbering).
        val unknownRow =
            listOf("\t20260901", "\t08:30:00", "", "1.00", "1000000.00", "SYN-UNKNOWN-TYPE", "")
                .joinToString(",") { "\"$it\"" }
        val port = RecordingIntakePort()
        val outcome = orchestrator(port).intake(cmbInput(session, listOf(cmbRow(), unknownRow)))

        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(2, accepted.records.size)
        assertEquals(1, accepted.newCandidateIds.size)
        val rejected = accepted.records.single { it.disposition == ImportIntakeRecordDisposition.PARSER_REJECTED }
        assertEquals("SPINE_CMB_UNKNOWN_TOKEN", rejected.diagnosticCode)
        // The raw token never reaches the outcome summary (D06 safe location).
        assertTrue(accepted.records.none { it.diagnosticCode == "SYN-UNKNOWN-TYPE" })
        assertEquals(1, port.snapshots.size)
    }

    // ---------------------------------------------------------------- L2 accepted-record bound

    @Test
    fun acceptedRecordBoundIsTypedWithTheActualCountAndWritesNothing() {
        val rows = (1..(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS + 1)).map { cmbRow() }
        val port = RecordingIntakePort()
        val idSource = CountingIntakeIdSource()
        val outcome =
            orchestrator(port, idSource).intake(cmbInput(newSession(), rows))

        val rejected = assertIs<ImportFileIntakeOutcome.Rejected>(outcome)
        val failure = assertIs<ImportIntakeBatchFailure.BatchExceedsLimit>(rejected.failure)
        assertEquals(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS + 1, failure.actualAcceptedRecords)
        assertEquals(0, port.snapshots.size)
        assertEquals(0, idSource.calls)
    }

    @Test
    fun exactlyTheBoundStillDispatches() {
        val rows = (1..IMPORT_INTAKE_MAX_ACCEPTED_RECORDS).map { cmbRow() }
        val port = RecordingIntakePort()
        val outcome = orchestrator(port).intake(cmbInput(newSession(), rows))
        assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS, port.snapshots.size)
    }

    // ---------------------------------------------------------------- format / charset gates

    /**
     * A-04.2 flip: CCB XLS on Android is no longer a typed (a)-gate rejection — the matrix
     * unit flipped to AVAILABLE after A-04.1's instrumented verification, so the intake
     * dispatches the real fixture bytes to the parser. The dispatch face must equal the
     * P-49 oracle of tests/fixtures/batch-bp01-ccb-a.xls: 13 accepted records reach the
     * spine (the recording port's snapshots) and 5 parser-rejected rows never do.
     */
    @Test
    fun ccbXlsOnAndroidNowDispatchesToTheParser() {
        val repositoryRoot =
            generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
                ?: error("repository root not found")
        val fixtureBytes = Files.readAllBytes(repositoryRoot.resolve("tests/fixtures").resolve("batch-bp01-ccb-a.xls"))

        val port = RecordingIntakePort()
        val outcome =
            orchestrator(port).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.CCB_XLS.identifier,
                    platform = ImportPlatformKind.ANDROID,
                    session = newSession(),
                    bytes = fixtureBytes,
                ),
            )

        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(18, accepted.records.size)
        assertEquals(13, accepted.records.count { it.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED })
        assertEquals(5, accepted.records.count { it.disposition == ImportIntakeRecordDisposition.PARSER_REJECTED })
        assertEquals(13, port.snapshots.size)
    }

    @Test
    fun unsupportedGb18030CharsetIsTypedAndNeverReachesTheParser() {
        val outcome =
            orchestrator(RecordingIntakePort(), charsetSupported = { false }).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.ALIPAY_CSV.identifier,
                    platform = ImportPlatformKind.DESKTOP,
                    session = newSession(),
                    bytes = ByteArray(0),
                ),
            )
        val rejected = assertIs<ImportFileIntakeOutcome.Rejected>(outcome)
        val failure = assertIs<ImportIntakeBatchFailure.FormatUnavailable>(rejected.failure)
        assertEquals(ImportFormatUnavailableReason.CHARSET_UNSUPPORTED, failure.reason)
        assertEquals(ImportFormatCapabilities.ALIPAY_CSV.identifier, failure.formatId)
    }

    @Test
    fun wholeBatchParserRejectionStaysTypedAndWritesNothing() {
        val port = RecordingIntakePort()
        val outcome =
            orchestrator(port).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.CMB_CSV.identifier,
                    platform = ImportPlatformKind.DESKTOP,
                    session = newSession(),
                    bytes = ByteArray(0),
                ),
            )
        val rejected = assertIs<ImportFileIntakeOutcome.Rejected>(outcome)
        val failure = assertIs<ImportIntakeBatchFailure.ParserRejected>(rejected.failure)
        assertEquals("INPUT_DECODE_FAILED", failure.diagnostic.code)
        assertEquals(0, port.snapshots.size)
    }

    // ---------------------------------------------------------------- D06 sanitization (plan 6.3)

    /**
     * D06 (plan section 6.3, spec sections 6.4/7 D06): sanitized intake diagnostics never echo
     * the original file content. Sensitive-looking synthetic tokens — a personal-looking name,
     * a `file:///` URI with a path, and a card-like number — are injected into the parsed bytes
     * both in a row that parses (its free-text remark carries them, so the spine snapshot
     * legitimately holds the parsed facts) and in a row that is typed-rejected, plus into bytes
     * that fail decoding whole-batch; every string field of the SURFACED outcomes (record
     * summaries and the batch diagnostic) must contain none of them, and the only reference
     * field carries the session's opaque handle (R-Q09-1), never content. The display name never
     * enters this layer at all: [ImportFileIntakeInput] carries bytes only, so no file-name
     * channel exists here (the session display-name surface is covered in app-ui).
     */
    @Test
    fun intakeDiagnosticsNeverEchoInjectedSensitiveFileContent() {
        val sensitiveName = "张三"
        val sensitiveUri = "file:///vault/synthetic-user/个人账单-2026-08.csv"
        val sensitiveCard = "6222020200112233445"
        val tokens = listOf(sensitiveName, sensitiveUri, sensitiveCard)
        val sensitiveRemark = "转账给$sensitiveName 卡号$sensitiveCard $sensitiveUri"
        // The remark is free text inside the raw row; a non-empty CMB remark must carry the
        // frozen leading tab, and the injected content must not contain a comma (it does not).
        val remarkRow =
            listOf("\t20260903", "\t09:15:00", "", "2.00", "999.00", "网联协议支付", "\t$sensitiveRemark")
                .joinToString(",") { "\"$it\"" }
        // The rejected row carries the same sensitive tokens in its type token and remark.
        val rejectedTypeRow =
            listOf("\t20260904", "\t10:00:00", "", "3.00", "996.00", "消费-$sensitiveName", "\t$sensitiveUri")
                .joinToString(",") { "\"$it\"" }

        val outcome =
            orchestrator(RecordingIntakePort()).intake(cmbInput(newSession(), listOf(remarkRow, rejectedTypeRow)))
        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(
            listOf(ImportIntakeRecordDisposition.INTAKE_ACCEPTED, ImportIntakeRecordDisposition.PARSER_REJECTED),
            accepted.records.map { it.disposition },
        )
        val recordFields =
            accepted.records
                .flatMap { record ->
                    listOf(record.diagnosticCode, record.diagnosticSeverity, record.diagnosticScope, record.diagnosticFieldRole)
                }.filterNotNull()
        // D-097 safe-location digests only: no raw row, name, URI or identifier text surfaces.
        tokens.forEach { token -> assertTrue(recordFields.none { it.contains(token) }) }

        // Whole-batch decode failure over poisoned bytes that embed the same tokens.
        val session = newSession()
        val poisonedBytes =
            ("\uFEFF日期,备注\n$sensitiveName,$sensitiveCard,").toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val rejectedOutcome =
            orchestrator(RecordingIntakePort()).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.CMB_CSV.identifier,
                    platform = ImportPlatformKind.DESKTOP,
                    session = session,
                    bytes = poisonedBytes,
                ),
            )
        val rejected = assertIs<ImportFileIntakeOutcome.Rejected>(rejectedOutcome)
        val failure = assertIs<ImportIntakeBatchFailure.ParserRejected>(rejected.failure)
        assertEquals("INPUT_DECODE_FAILED", failure.diagnostic.code)
        val diagnosticFields =
            listOfNotNull(
                failure.diagnostic.code,
                failure.diagnostic.severity,
                failure.diagnostic.scope,
                failure.diagnostic.inputRef,
                failure.diagnostic.fieldRole,
            )
        tokens.forEach { token -> assertTrue(diagnosticFields.none { it.contains(token) }) }
        // The only reference field is the session's opaque pick handle (R-Q09-1: a random
        // UUIDv7, never derived from URI, file name or content).
        assertEquals(session.inputRef, failure.diagnostic.inputRef)
    }
}
