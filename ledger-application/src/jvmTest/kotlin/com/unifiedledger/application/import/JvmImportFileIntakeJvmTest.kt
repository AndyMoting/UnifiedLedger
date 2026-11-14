package com.unifiedledger.application.import

import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_INTAKE_MAX_ACCEPTED_RECORDS
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatAvailability
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportFormatUnavailableReason
import com.unifiedledger.application.ImportFundingState
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
import com.unifiedledger.application.SelfEntryMatchBasis
import com.unifiedledger.application.SPINE_NO_CHANGE_REASON_CODE
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.application.aggregateImportIntakeRecords
import com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

    // ---------------------------------------------------------------- storage failure mid-batch

    /**
     * Fails the commit of the Nth dispatched record with the production face — a
     * SQLiteFullException raised inside the commit transaction (DEF-IMPORT-ENOSPC-CRASH-01).
     */
    private class FailingIntakePort(
        private val failOnDispatch: Int,
    ) : ImportIntakeCommitPort {
        val snapshots = mutableListOf<ImportIntakeSnapshot>()

        override fun commitIntake(
            identity: ImportRequestIdentity,
            snapshot: ImportIntakeSnapshot,
            allocateIds: (requiredDuplicateIds: Int) -> ImportIntakeIds,
        ): ImportIntakeResult {
            snapshots += snapshot
            if (snapshots.size == failOnDispatch) {
                throw IllegalStateException("database or disk is full (code 13 SQLITE_FULL)")
            }
            val ids = allocateIds(0)
            return ImportIntakeResult.Accepted(
                receipt = ImportReceipt(identity.requestId, ids.sourceId, ids.evidenceId, ids.candidateId, null, null),
                returnedIds = emptyList(),
            )
        }
    }

    /**
     * DEF-IMPORT-ENOSPC-CRASH-01 (D-276 item 2): an intake exception stops the dispatch instead
     * of escaping. The assertions are exactly this boundary's claims — the call returns rather
     * than throwing, every later record is never dispatched, and the batch comes back as the
     * payload-free typed rejection. Transaction rollback and same-requestId retry stay pinned by
     * the adapter-level injected-failure tests (P406 and the ImportSpine EndToEnd suites), not
     * re-proved here.
     */
    @Test
    fun midBatchIntakeFailureStopsTheDispatchAndIsTypedWithNoPayload() {
        val port = FailingIntakePort(failOnDispatch = 2)
        val idSource = CountingIntakeIdSource()
        val rows = listOf(cmbRow(), cmbRow(date = "20260902"), cmbRow(date = "20260903"))

        val outcome = orchestrator(port, idSource).intake(cmbInput(newSession(), rows))

        val rejected = assertIs<ImportFileIntakeOutcome.Rejected>(outcome)
        assertEquals(ImportIntakeBatchFailure.StorageUnavailable, rejected.failure)
        // Dispatch stopped at the failure: the third record never reached the spine, and the
        // record committed before it consumed exactly one id.
        assertEquals(2, port.snapshots.size)
        assertEquals(listOf(0, 1), port.snapshots.map { it.recordOrdinal })
        assertEquals(1, idSource.calls)
        // Zero payload (D06): no exception text reaches the outcome.
        assertEquals("StorageUnavailable", rejected.failure.toString())
        assertFalse(rejected.failure.toString().contains("SQLITE_FULL"))
    }

    /**
     * A commit port that always fails with the given throwable, so each test can pin what the
     * dispatch boundary must NOT convert into the typed storage failure.
     */
    private class ThrowingIntakePort(
        private val throwable: Throwable,
    ) : ImportIntakeCommitPort {
        val snapshots = mutableListOf<ImportIntakeSnapshot>()

        override fun commitIntake(
            identity: ImportRequestIdentity,
            snapshot: ImportIntakeSnapshot,
            allocateIds: (requiredDuplicateIds: Int) -> ImportIntakeIds,
        ): ImportIntakeResult {
            snapshots += snapshot
            throw throwable
        }
    }

    /**
     * The storage boundary converts `Exception` only (DEF-IMPORT-ENOSPC-CRASH-01, D-276 item 2):
     * a fatal `Error` is not a storage verdict and must escape. The `RestorePreflightUseCaseTest`
     * fatal-error precedent is the same rule on the restore path.
     */
    @Test
    fun aFatalErrorFromTheSpinePropagatesInsteadOfBecomingTheTypedFailure() {
        val port = ThrowingIntakePort(OutOfMemoryError("injected"))
        assertFailsWith<OutOfMemoryError> {
            orchestrator(port).intake(cmbInput(newSession(), listOf(cmbRow())))
        }
        assertEquals(1, port.snapshots.size)
    }

    /**
     * Coroutine cancellation is not a storage verdict either: the boundary rethrows it, so a
     * cancelled dispatch can never surface as a retryable typed "import aborted".
     */
    @Test
    fun aCancellationFromTheSpineIsRethrownInsteadOfBecomingTheTypedFailure() {
        val port = ThrowingIntakePort(CancellationException("cancelled"))
        assertFailsWith<CancellationException> {
            orchestrator(port).intake(cmbInput(newSession(), listOf(cmbRow())))
        }
        assertEquals(1, port.snapshots.size)
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

    // ---------------------------------------------------------------- P8-04 i2: the fifth matrix format (D-13/D-16)

    /**
     * The orchestrator dispatches SELF_ENTRY_XLSX to the new parser exactly like the four
     * frozen arms: the generated template's 3 example rows reach the spine as their D-16
     * facts (with the 币种-blank rows riding the incomplete-candidate path), and the
     * D-18 missing-leg pin is observable on the snapshot.
     */
    @Test
    fun selfEntryXlsxDispatchesToTheNewParserWithTheD16Facts() {
        val port = RecordingIntakePort()
        val templateBytes = SelfEntryTemplateGenerator.generateTemplate()
        val outcome =
            orchestrator(port).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                    platform = ImportPlatformKind.DESKTOP,
                    session = newSession(),
                    bytes = templateBytes,
                ),
            )

        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(3, accepted.records.size)
        assertTrue(accepted.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED })
        assertEquals(3, port.snapshots.size)
        val expense = port.snapshots[0]
        assertEquals(com.unifiedledger.application.ImportRecordKind.ORDINARY_FLOW_SOURCE, expense.recordKind)
        assertEquals("out", expense.facts.directionToken)
        assertEquals(1234L, expense.facts.amountMinor)
        assertEquals("2026-01-01T10:00:00+08:00", expense.facts.occurredAt)
        assertEquals(ImportFundingState.SETTLED, expense.facts.fundingState)
        val transfer = port.snapshots[2]
        assertEquals(com.unifiedledger.application.ImportRecordKind.TRANSFER_FLOW_SOURCE, transfer.recordKind)
        assertEquals(ImportFundingState.SETTLED, transfer.facts.fundingState)
    }

    /**
     * D-13: the fifth format enters the matrix in the pending state on Android, so the
     * preserved (a) gate fires typed with PENDING_DEVICE_VERIFICATION — zero read, zero
     * parse, zero dispatch (the branch's first live exerciser since the CCB flip).
     */
    @Test
    fun selfEntryXlsxOnAndroidIsTheTypedPendingGateWithZeroDispatch() {
        val port = RecordingIntakePort()
        val outcome =
            orchestrator(port).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                    platform = ImportPlatformKind.ANDROID,
                    session = newSession(),
                    bytes = ByteArray(0),
                ),
            )

        val rejected = assertIs<ImportFileIntakeOutcome.Rejected>(outcome)
        val failure = assertIs<ImportIntakeBatchFailure.FormatUnavailable>(rejected.failure)
        assertEquals(ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier, failure.formatId)
        assertEquals(ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION, failure.reason)
        assertEquals(0, port.snapshots.size)
    }

    /** The matrix declaration itself (D-13): five formats, Android pending / Desktop available on the fifth. */
    @Test
    fun theMatrixCarriesTheFifthDescriptorAsPendingOnAndroidAndAvailableOnDesktop() {
        assertEquals(5, ImportFormatCapabilities.ALL.size)
        assertEquals("self-entry-xlsx", ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier.value)
        assertEquals(
            ImportFormatAvailability.PENDING_DEVICE_VERIFICATION,
            ImportFormatCapabilities.SELF_ENTRY_XLSX.availabilityOn(ImportPlatformKind.ANDROID),
        )
        assertEquals(
            ImportFormatAvailability.AVAILABLE,
            ImportFormatCapabilities.SELF_ENTRY_XLSX.availabilityOn(ImportPlatformKind.DESKTOP),
        )
    }

    /**
     * P8-04 i3a (D-18): with a catalog injected, the self-entry arm name-matches every
     * accepted row — the D-19 basis rides the summaries (source text -> target name), a
     * matched row keeps its parsed completeness, and a matched-failure row is demoted from
     * VALID_COMPLETE to VALID_INCOMPLETE (the D-6 semantics: low confidence, outside the
     * duplicate-family gate). Both rows fill the 币种 cell, so each parses VALID_COMPLETE
     * and only the match outcome separates them.
     */
    @Test
    fun selfEntryWithACatalogMatchesNamesCarriesTheBasisAndDemotesUnmatchedRows() {
        val port = RecordingIntakePort()
        val catalogLedgerId = LedgerId("ledger-p804-i3a-catalog")

        fun account(
            id: String,
            name: String,
        ): Account =
            Account(
                id = AccountId(id),
                ledgerId = catalogLedgerId,
                kind = AccountKind.ASSET,
                currency = CurrencyUnit("CNY", 2),
                ownedByUser = true,
                realAccount = true,
                name = name,
            )

        fun category(
            id: String,
            name: String,
            parentId: CategoryId?,
        ): Category =
            Category(
                id = CategoryId(id),
                ledgerId = catalogLedgerId,
                parentId = parentId,
                postingAccountId = null,
                active = true,
                name = name,
            )

        val catalog =
            when (
                val result =
                    LedgerCatalog.create(
                        accounts = listOf(account("acc-cash", "现金"), account("acc-bank", "银行卡")),
                        categories =
                            listOf(
                                category("cat-food-parent", "食品", null),
                                category("cat-food", "餐饮", CategoryId("cat-food-parent")),
                                category("cat-salary", "工资", null),
                            ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> throw IllegalStateException("fixture catalog rejected")
            }

        // Two fresh rows behind the pristine header: a matched expense (分类 餐饮, 账户 现金)
        // and a matched-failure expense (账户 不存在的账户 — demoted, basis carries the reason).
        val bytes =
            selfEntryVariant(
                listOf(
                    listOf("支出", "2026-03-01 08:00:00", "5.00", "CNY", "餐饮", "", "现金", "", "matched"),
                    listOf("支出", "2026-03-02 09:00:00", "6.00", "CNY", "餐饮", "", "不存在账户", "", "unmatched"),
                ),
            )
        val outcome =
            orchestrator(port).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                    platform = ImportPlatformKind.DESKTOP,
                    session = newSession(),
                    bytes = bytes,
                    catalog = catalog,
                ),
            )

        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(2, accepted.records.size)
        assertTrue(accepted.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED })
        assertTrue(port.snapshots.all { it.candidateRuleOverride == "self_entry_v1" })
        // Row 0: matched — the basis carries the verbatim source texts and the catalog
        // target names; the parsed VALID_COMPLETE stays untouched.
        val matchedBasis = assertIs<SelfEntryMatchBasis>(accepted.records[0].matchBasis)
        assertEquals("餐饮", matchedBasis.categorySourceText)
        assertEquals("餐饮", matchedBasis.categoryTargetName)
        assertNull(matchedBasis.categoryUnmatchedReason)
        assertEquals("现金", matchedBasis.fundingAccountSourceText)
        assertEquals("现金", matchedBasis.fundingAccountTargetName)
        assertFalse(matchedBasis.unconsumedCategoryText)
        assertEquals(ImportCompleteness.VALID_COMPLETE, port.snapshots[0].completeness)
        // Row 1: matched-failure — the verbatim reason token rides the basis and the parsed
        // VALID_COMPLETE is DEMOTED to VALID_INCOMPLETE (the D-6 failure face: low
        // confidence, outside the duplicate-family gate).
        val unmatchedBasis = assertIs<SelfEntryMatchBasis>(accepted.records[1].matchBasis)
        assertEquals("不存在账户", unmatchedBasis.fundingAccountSourceText)
        assertNull(unmatchedBasis.fundingAccountTargetName)
        assertEquals("ACCOUNT_NOT_FOUND", unmatchedBasis.fundingAccountUnmatchedReason)
        assertEquals(ImportCompleteness.VALID_INCOMPLETE, port.snapshots[1].completeness)
    }

    /**
     * P8-04 i3a (D-18): an input without a catalog (every pre-i3a construction) leaves the
     * self-entry arm's completeness untouched and carries no basis — matching is simply
     * absent, never a silent all-unmatched face. The row fills the 币种 cell, so it parses
     * VALID_COMPLETE and the absence of a catalog is exactly what keeps it there. The
     * arm's rule override still rides: every self-entry candidate lands as
     * `self_entry_v1`.
     */
    @Test
    fun selfEntryWithoutACatalogKeepsTheCompletenessAndCarriesNoBasis() {
        val port = RecordingIntakePort()
        val outcome =
            orchestrator(port).intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                    platform = ImportPlatformKind.DESKTOP,
                    session = newSession(),
                    bytes =
                        selfEntryVariant(
                            listOf(
                                listOf("支出", "2026-03-03 10:00:00", "7.00", "CNY", "餐饮", "", "现金", "", "no catalog"),
                            ),
                        ),
                ),
            )

        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
        assertEquals(1, accepted.records.size)
        assertEquals(1, port.snapshots.size)
        assertNull(accepted.records.single().matchBasis)
        assertEquals("self_entry_v1", port.snapshots.single().candidateRuleOverride)
        // The parsed VALID_COMPLETE is untouched: without a catalog nothing demotes it.
        assertEquals(ImportCompleteness.VALID_COMPLETE, port.snapshots.single().completeness)
    }

    // ---------------------------------------------------------------- P8-04 i3a: the self-entry variant workbook

    /**
     * A self-entry workbook variant: the generated six parts with the sheet's data rows
     * replaced by the given 9-column string rows (the generated header row spliced
     * verbatim; new strings appended to the generated sharedStrings pool) — the
     * SelfEntryXlsxParserJvmTest string-editing fixture strategy, in its minimal form.
     */
    private fun selfEntryVariant(rows: List<List<String>>): ByteArray {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(SelfEntryTemplateGenerator.generateTemplate())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val sheetPart = "xl/worksheets/sheet1.xml"
        val sharedStringsPart = "xl/sharedStrings.xml"
        val sheetXml = parts.getValue(sheetPart).toString(Charsets.UTF_8)
        val sharedStringsXml = parts.getValue(sharedStringsPart).toString(Charsets.UTF_8)

        fun escapeXml(value: String): String =
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

        val pool = LinkedHashMap<String, Int>()
        sharedStringsXml
            .substringBefore("</sst>")
            .split("<si><t>")
            .drop(1)
            .forEachIndexed { index, item -> pool[item.substringBefore("</t>")] = index }
        val originalCount = pool.size

        val headerRow = sheetXml.substringAfter("<sheetData>").substringBefore("</row>") + "</row>"
        val dataRows =
            rows
                .mapIndexed { index, values ->
                    val rowIndex = index + 2
                    "<row r=\"$rowIndex\">" +
                        values
                            .mapIndexed { column, value ->
                                val reference = (('A' + column).toString()) + rowIndex
                                if (column == 2) {
                                    // The amount column: an exact-text numeric cell (the generator's own shape).
                                    "<c r=\"$reference\"><v>${escapeXml(value)}</v></c>"
                                } else {
                                    val sharedIndex = pool.getOrPut(value) { pool.size }
                                    "<c r=\"$reference\" t=\"s\"><v>$sharedIndex</v></c>"
                                }
                            }.joinToString("") +
                        "</row>"
                }.joinToString("")
        val additions =
            pool.entries
                .drop(originalCount)
                .joinToString("") { "<si><t>${escapeXml(it.key)}</t></si>" }
        val newSharedStrings =
            sharedStringsXml
                .replace("</sst>", additions + "</sst>")
                .replace("count=\"$originalCount\"", "count=\"${pool.size}\"")
                .replace("uniqueCount=\"$originalCount\"", "uniqueCount=\"${pool.size}\"")
        parts[sheetPart] =
            (
                sheetXml.substringBefore("<sheetData>") +
                    "<sheetData>" +
                    headerRow +
                    dataRows +
                    "</sheetData>" +
                    sheetXml.substringAfter("</sheetData>")
            ).toByteArray(Charsets.UTF_8)
        parts[sharedStringsPart] = newSharedStrings.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
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
