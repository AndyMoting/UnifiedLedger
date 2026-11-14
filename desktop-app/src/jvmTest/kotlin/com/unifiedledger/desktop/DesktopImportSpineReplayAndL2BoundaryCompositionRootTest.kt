package com.unifiedledger.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.IMPORT_INTAKE_MAX_ACCEPTED_RECORDS
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateDetailResult
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportDuplicateReviewsResult
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportIntakeBatchFailure
import com.unifiedledger.application.ImportIntakeRecordDisposition
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRowsPageResult
import com.unifiedledger.ui.ImportConfirmUseCaseSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P8-04 i4b acceptance vectors on the REAL desktop ledger (spec
 * `docs/specs/2026-11-14-p8-04-self-entry-import-design.md` §5): V-7 (the L2 boundary) and
 * V-4 (replay without a second posting). The vectors drive the wired `buildLedgerGraph`
 * composition root — the DesktopImportBatchConfirmationCompositionRootTest shape
 * (in-memory SQLite, the real `SqlDelightImportSpineStore`, the real jvmMain orchestrator,
 * the real review reads and confirm/duplicate surfaces). The ledger-application jvmTest
 * commit port is a recording double, so no test there can prove a real-store vector.
 *
 * V-7 (two legs): 10,000 accepted rows (= `IMPORT_INTAKE_MAX_ACCEPTED_RECORDS`) land on the
 * real ledger — the candidate/source row counts are read back through the graph's own SQL
 * surface (`graph.database` is composition-root test read-back only); 10,001 rows are the
 * typed `BatchExceedsLimit(actualAcceptedRecords = 10_001)` with ZERO writes (the (d) gate
 * stands before the dispatch fold — the JvmImportFileIntakeJvmTest:267-279 shape, now proven
 * against a real store instead of a recording port). Count assertions only — no
 * per-candidate traversal (CI wall-clock discipline).
 *
 * V-4 (three legs): ① the same pick session re-dispatched byte-identically is the spine's
 * equivalent replay — every record `NoChange` (`equivalent_replay`), the aggregation folds
 * to `NoChangeAll`, the candidate count does not move; ② the same bytes through a NEW
 * session (a fresh inputRef misses the spine identity, so new candidates land) must create
 * zero second postings — and, because both intakes pin settled status with identical
 * five-field tuples, the intake's own duplicate-family gate writes one
 * `EXACT_BUSINESS_TUPLE` row per overlapping pair (DEFERRED, the reachable three-value
 * state); ③ the cross-format leg — the CMB official row first, then a self-entry row on the
 * same business tuple — produces the `EXACT_BUSINESS_TUPLE` duplicate candidate (never an
 * automatic merge, D-20), and the wired three-value duplicate-review surface (the
 * composition root's `importDuplicateReview` + its per-intent id mint) disposes the
 * duplicate through the real spine store, after which the subject candidate is
 * confirm-blocked (`SPINE_DUPLICATE_NOT_CONFIRMABLE` — the disposal is effective).
 *
 * All row data is synthetic; amounts ride the exact decimal-text contract; every leg owns
 * its own driver/ledger (no cross-leg pollution).
 */
class DesktopImportSpineReplayAndL2BoundaryCompositionRootTest {
    private fun withGraph(block: (graph: DesktopLedgerGraph, driver: JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            block(buildLedgerGraph(driver, createSchema = true), driver)
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ synthetic fixtures

    /**
     * One synthetic 9-column self-entry data row: every row carries a distinct 日期时间 and
     * 金额 (the spine identity is (ledger, inputRef, ordinal) — intra-file business
     * duplicates are legal, but distinct facts keep the duplicate-family gate silent inside
     * the V-7 and V-4①/② files until the dedicated duplicate legs exercise it).
     */
    private fun selfEntryRow(ordinal: Int): List<String> =
        listOf(
            "支出",
            "2026-" + (3 + ordinal % 10).toString().padStart(2, '0') + "-01 08:00:00",
            (ordinal + 1).toString() + "." + (ordinal % 100).toString().padStart(2, '0'),
            "CNY",
            "餐饮",
            "",
            "现金",
            "",
            "synthetic-i4b",
        )

    /**
     * The generated template with its sheet's data rows replaced by [rows] (the
     * string-editing variant shape of `SelfEntryTemplateGenerator.generateTemplate()`; the
     * generated header row is spliced verbatim, new strings append to the generated
     * sharedStrings pool, and the amount column is an exact-text numeric cell — the
     * generator's own shape).
     */
    private fun selfEntryVariant(rows: List<List<String>>): ByteArray {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipStreams.readAll(
            com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator.generateTemplate(),
        ).forEach { (name, bytes) -> parts[name] = bytes }
        val sheetPart = "xl/worksheets/sheet1.xml"
        val sharedStringsPart = "xl/sharedStrings.xml"
        val sheetXml = parts.getValue(sheetPart).toString(Charsets.UTF_8)
        val sharedStringsXml = parts.getValue(sharedStringsPart).toString(Charsets.UTF_8)

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
                    val cells =
                        values
                            .mapIndexed { column, value ->
                                val reference = (('A' + column).toString()) + rowIndex
                                if (column == 2) {
                                    "<c r=\"$reference\"><v>$value</v></c>"
                                } else {
                                    val sharedIndex = pool.getOrPut(value) { pool.size }
                                    "<c r=\"$reference\" t=\"s\"><v>$sharedIndex</v></c>"
                                }
                            }.joinToString("")
                    "<row r=\"$rowIndex\">" + cells + "</row>"
                }.joinToString("")
        val additions =
            pool.entries
                .drop(originalCount)
                .joinToString("") { "<si><t>${it.key}</t></si>" }
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
        return ZipStreams.writeAll(parts)
    }

    /** The frozen BP-01 CMB container with ONE data row (anonymous synthetic values). */
    private fun cmbSingleRowBill(
        date: String,
        expense: String,
    ): ByteArray {
        val comment = (0..5).map { "\"SYN-CMB-META-$it\"" }
        val header = "\"交易日期\",\"交易时间\",\"收入\",\"支出\",\"余额\",\"交易类型\",\"交易备注\""
        val dataRow =
            listOf("\t$date", "\t10:11:12", "", expense, "1000.00", "网联协议支付", "\tSYN-CMB-I4B-REMARK")
                .joinToString(",") { "\"$it\"" }
        val lines = comment + listOf("\"\"") + listOf(header) + listOf(dataRow) + listOf("\"\"")
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ read-back (composition-root test SQL surface)

    private fun scalarCount(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long {
        var value = -1L
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                if (cursor.next().value) value = cursor.getLong(0) ?: 0L
                QueryResult.Unit
            },
            0,
        )
        return value
    }

    private fun candidateCount(
        graph: DesktopLedgerGraph,
        driver: JdbcSqliteDriver,
    ): Long = scalarCount(driver, "SELECT count(*) FROM import_candidate WHERE ledger_id = '${graph.ledgerId.value}'")

    private fun sourceCount(
        graph: DesktopLedgerGraph,
        driver: JdbcSqliteDriver,
    ): Long = scalarCount(driver, "SELECT count(*) FROM import_source_record WHERE ledger_id = '${graph.ledgerId.value}'")

    private fun duplicateCount(
        graph: DesktopLedgerGraph,
        driver: JdbcSqliteDriver,
    ): Long = scalarCount(driver, "SELECT count(*) FROM import_duplicate_candidate WHERE ledger_id = '${graph.ledgerId.value}'")

    private fun formalTransactionCount(
        graph: DesktopLedgerGraph,
        driver: JdbcSqliteDriver,
    ): Long = scalarCount(driver, "SELECT count(*) FROM ledger_transaction WHERE ledger_id = '${graph.ledgerId.value}'")

    // ------------------------------------------------------------------ V-7: the L2 boundary (real ledger)

    /** V-7 accepted leg: exactly the L2 bound dispatches and every row lands on the real store. */
    @Test
    fun exactlyTheL2BoundLandsTenThousandCandidatesOnTheRealLedger() {
        withGraph { graph, driver ->
            val bytes =
                selfEntryVariant(
                    (0 until IMPORT_INTAKE_MAX_ACCEPTED_RECORDS).map(::selfEntryRow),
                )
            val outcome =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = graph.facade.importIntakeSessionFactory()!!,
                        bytes = bytes,
                    ),
                )

            val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(outcome)
            assertEquals(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS, accepted.records.size)
            assertEquals(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS, accepted.newCandidateIds.size)
            assertTrue(
                "every dispatched record must be a new intake",
                accepted.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_ACCEPTED },
            )
            // Read back through the real store: candidate and source row counts equal the bound.
            assertEquals(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS.toLong(), candidateCount(graph, driver))
            assertEquals(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS.toLong(), sourceCount(graph, driver))
            assertEquals(0L, duplicateCount(graph, driver))
        }
    }

    /**
     * V-7 over-bound leg: one row over the bound is the typed `BatchExceedsLimit(actual =
     * 10_001)` and the real store reads back ZERO rows — the (d) gate stands before the
     * dispatch fold, so the whole file writes nothing.
     */
    @Test
    fun tenThousandAndOneRowsAreTheTypedOverBoundRejectionWithZeroRealLedgerWrites() {
        withGraph { graph, driver ->
            val bytes =
                selfEntryVariant(
                    (0..IMPORT_INTAKE_MAX_ACCEPTED_RECORDS).map(::selfEntryRow),
                )
            val outcome =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = graph.facade.importIntakeSessionFactory()!!,
                        bytes = bytes,
                    ),
                )

            val rejected = assertIs<ImportFileIntakeOutcome.Rejected>(outcome)
            val failure = assertIs<ImportIntakeBatchFailure.BatchExceedsLimit>(rejected.failure)
            assertEquals(IMPORT_INTAKE_MAX_ACCEPTED_RECORDS + 1, failure.actualAcceptedRecords)
            // Zero writes on the real ledger: nothing was dispatched, nothing persisted.
            assertEquals(0L, candidateCount(graph, driver))
            assertEquals(0L, sourceCount(graph, driver))
            assertEquals(0L, duplicateCount(graph, driver))
        }
    }

    // ------------------------------------------------------------------ V-4 ①: same-session equivalent replay

    /**
     * V-4 ①: the SAME pick session re-dispatched with the same bytes is the spine's
     * session-level equivalent replay — the request ids are the session's memoized ids, each
     * claim resolves `NoChange` (`equivalent_replay`), the aggregation folds to
     * `NoChangeAll`, and the candidate count does not move (zero second writes).
     */
    @Test
    fun theSameSessionReplayIsNoChangeAllWithZeroSecondWrites() {
        withGraph { graph, driver ->
            val session = graph.facade.importIntakeSessionFactory()!!
            val bytes = selfEntryVariant(listOf(selfEntryRow(0), selfEntryRow(1), selfEntryRow(2)))
            val first =
                assertIs<ImportFileIntakeOutcome.Accepted>(
                    graph.facade.importFileIntake!!.intake(
                        ImportFileIntakeInput(
                            format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                            platform = ImportPlatformKind.DESKTOP,
                            session = session,
                            bytes = bytes,
                        ),
                    ),
                )
            assertEquals(3, first.newCandidateIds.size)
            val candidatesAfterFirst = candidateCount(graph, driver)
            assertEquals(3L, candidatesAfterFirst)

            // The replay: same session identity (same inputRef + memoized requestIds), same bytes.
            val replay =
                graph.facade.importFileIntake!!.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                        platform = ImportPlatformKind.DESKTOP,
                        session = session,
                        bytes = bytes,
                    ),
                )
            val noChange = assertIs<ImportFileIntakeOutcome.NoChangeAll>(replay)
            assertEquals(3, noChange.records.size)
            assertTrue(
                "every replayed record must be the spine's INTAKE_NO_CHANGE",
                noChange.records.all { it.disposition == ImportIntakeRecordDisposition.INTAKE_NO_CHANGE },
            )
            // Zero second writes: the candidate table did not move.
            assertEquals(candidatesAfterFirst, candidateCount(graph, driver))
            assertEquals(3L, sourceCount(graph, driver))
        }
    }

    // ------------------------------------------------------------------ V-4 ②: cross-session same file

    /**
     * V-4 ②: the same file through a NEW pick session misses the spine identity (a fresh
     * inputRef), so new candidates land — but zero second POSTINGS are created, and because
     * both intakes pin settled status with identical five-field tuples, the intake's own
     * duplicate-family gate writes one `EXACT_BUSINESS_TUPLE` row per overlapping pair
     * (DEFERRED — the three-value disposal's reachable state).
     */
    @Test
    fun theCrossSessionSameFileLandsNewCandidatesAndDuplicateFamiliesWithoutASecondPosting() {
        withGraph { graph, driver ->
            val bytes = selfEntryVariant(listOf(selfEntryRow(0), selfEntryRow(1), selfEntryRow(2)))
            val first =
                assertIs<ImportFileIntakeOutcome.Accepted>(
                    graph.facade.importFileIntake!!.intake(
                        ImportFileIntakeInput(
                            format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                            platform = ImportPlatformKind.DESKTOP,
                            session = graph.facade.importIntakeSessionFactory()!!,
                            bytes = bytes,
                        ),
                    ),
                )
            assertEquals(3, first.newCandidateIds.size)
            assertEquals(0L, formalTransactionCount(graph, driver))

            // A fresh session (a new opaque handle): the same bytes again.
            val second =
                assertIs<ImportFileIntakeOutcome.Accepted>(
                    graph.facade.importFileIntake!!.intake(
                        ImportFileIntakeInput(
                            format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                            platform = ImportPlatformKind.DESKTOP,
                            session = graph.facade.importIntakeSessionFactory()!!,
                            bytes = bytes,
                        ),
                    ),
                )
            // New candidates (a new spine identity per row), each carrying its duplicate family.
            assertEquals(3, second.newCandidateIds.size)
            assertEquals(6L, candidateCount(graph, driver))
            // Zero second posting: no formal transaction exists after both intakes.
            assertEquals(0L, formalTransactionCount(graph, driver))
            // The duplicate-family gate fired once per new source row.
            assertEquals(3L, duplicateCount(graph, driver))
            assertEquals(
                3L,
                scalarCount(
                    driver,
                    "SELECT count(*) FROM import_duplicate_candidate WHERE ledger_id = '${graph.ledgerId.value}' " +
                        "AND kind = 'EXACT_BUSINESS_TUPLE'",
                ),
            )
            // The review read exposes the six candidates, each new one carrying its
            // duplicate row DEFERRED (the reachable three-value state) — never a merged or
            // auto-resolved face. pageSize counts CANDIDATES; one candidate contributes its
            // base row plus one row per duplicate, so six candidates read as nine rows.
            val rows =
                assertIs<ImportReviewRowsPageResult.Page>(
                    graph.facade.queryImportReviewRows!!.queryPage(graph.ledgerId, pageSize = 100L, afterCandidateId = null),
                ).rows
            assertEquals(6, rows.map { it.candidateId }.toSet().size)
            assertEquals(3, rows.count { it.duplicateStatus == ImportDuplicateStatus.DEFERRED })
            assertEquals(6, rows.filter { it.duplicateStatus == null }.map { it.candidateId }.toSet().size)
            assertTrue(rows.all { it.candidateStatus == "pending_confirmation" })
        }
    }

    // ------------------------------------------------------------------ V-4 ③: the cross-format leg + the three-value disposal

    /**
     * V-4 ③: the CMB official row lands first, then a self-entry row on the SAME business
     * tuple. The fingerprint does not carry the format and the inputRefs differ, so the spine
     * identity never matches — the cross-format equivalence is the duplicate-family gate's
     * `EXACT_BUSINESS_TUPLE` candidate (D-20), never an automatic merge. The wired
     * three-value duplicate-review surface then disposes the duplicate through the real spine
     * store, and the disposed candidate is confirm-blocked (`SPINE_DUPLICATE_NOT_CONFIRMABLE`):
     * the three-value disposal is reachable and effective on the real ledger.
     */
    @Test
    fun aSelfEntryRowOverAnIntakenCmbOfficialRowGeneratesTheExactBusinessTupleAndTheThreeValueDisposalIsReachable() {
        withGraph { graph, driver ->
            // Leg 1: the official CMB row (2026-04-01 10:11:12 +08:00, 5.00 CNY out, settled).
            val cmbOutcome =
                assertIs<ImportFileIntakeOutcome.Accepted>(
                    graph.facade.importFileIntake!!.intake(
                        ImportFileIntakeInput(
                            format = ImportFormatCapabilities.CMB_CSV.identifier,
                            platform = ImportPlatformKind.DESKTOP,
                            session = graph.facade.importIntakeSessionFactory()!!,
                            bytes = cmbSingleRowBill(date = "20260401", expense = "5.00"),
                        ),
                    ),
                )
            assertEquals(1, cmbOutcome.newCandidateIds.size)
            assertEquals(1L, candidateCount(graph, driver))
            assertEquals(0L, duplicateCount(graph, driver))

            // Leg 2: the self-entry row on the SAME business tuple — 2026-04-01 10:11:12
            // parses to the identical +08:00 ISO text as the CMB row's frozen offset shape,
            // the same 500 minor units, CNY/2, "out" and "settled" (both arms pin these).
            val selfEntryOutcome =
                assertIs<ImportFileIntakeOutcome.Accepted>(
                    graph.facade.importFileIntake!!.intake(
                        ImportFileIntakeInput(
                            format = ImportFormatCapabilities.SELF_ENTRY_XLSX.identifier,
                            platform = ImportPlatformKind.DESKTOP,
                            session = graph.facade.importIntakeSessionFactory()!!,
                            bytes =
                                selfEntryVariant(
                                    listOf(
                                        listOf("支出", "2026-04-01 10:11:12", "5.00", "CNY", "餐饮", "", "现金", "", "i4b-cross-format"),
                                    ),
                                ),
                        ),
                    ),
                )
            assertEquals(1, selfEntryOutcome.newCandidateIds.size)
            // Two sources, two candidates — the cross-format equivalence is NOT a merge.
            assertEquals(2L, candidateCount(graph, driver))
            assertEquals(2L, sourceCount(graph, driver))
            assertEquals(0L, formalTransactionCount(graph, driver))
            // The self-entry candidate carries the EXACT_BUSINESS_TUPLE family row.
            assertEquals(1L, duplicateCount(graph, driver))
            assertEquals(
                1L,
                scalarCount(
                    driver,
                    "SELECT count(*) FROM import_duplicate_candidate WHERE ledger_id = '${graph.ledgerId.value}' " +
                        "AND kind = 'EXACT_BUSINESS_TUPLE'",
                ),
            )

            // The duplicate-review surface is wired and the duplicate is DEFERRED.
            val duplicates =
                assertIs<ImportDuplicateReviewsResult.Reviews>(
                    graph.facade.queryImportDuplicateReviews!!.query(graph.ledgerId, selfEntryOutcome.newCandidateIds.single()),
                ).reviews
            assertEquals(1, duplicates.size)
            val duplicate = duplicates.single()
            assertEquals(ImportDuplicateStatus.DEFERRED, duplicate.latestStatus)

            // The composition-root three-value disposal: the UI's CONFIRMED_DUPLICATE through
            // the real spine store (the host's request shape — the per-intent id triple and
            // the one clock sample Q09.4 pins: a review is a processing time, sampled once).
            val ids = checkNotNull(graph.facade.importDuplicateReviewIds?.invoke())
            val now = graph.facade.ledgerClock.now().toString()
            val review =
                graph.facade.importDuplicateReview!!.execute(
                    ImportDuplicateReviewRequest(
                        identity = ImportRequestIdentity(graph.ledgerId, ids.requestId),
                        candidateId = duplicate.duplicateCandidateId,
                        expectedComparisonFingerprint = duplicate.comparisonFingerprint,
                        decision = ImportDuplicateStatus.CONFIRMED_DUPLICATE,
                        reasonToken = "user-reviewed",
                        reviewedAt = now,
                        reviewerReference = "p704-import-review-ui",
                        generatedAt = now,
                        reviewId = ids.reviewId,
                        historyId = ids.historyId,
                    ),
                )
            assertIs<ImportDuplicateReviewResult.Accepted>(review)

            // The disposal is effective on the real ledger: the self-entry candidate is now
            // confirm-blocked (the blocking verdict blocks its own subject's confirmation).
            val detail =
                assertIs<ImportCandidateDetailResult.Found>(
                    graph.facade.queryImportCandidateDetail!!.query(graph.ledgerId, selfEntryOutcome.newCandidateIds.single()),
                ).detail
            val confirmUseCases = assertIs<ImportConfirmUseCaseSet>(graph.facade.importConfirmUseCases?.invoke())
            val blocked =
                confirmUseCases.ordinaryFlow!!.execute(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(graph.ledgerId, ImportRequestId("synthetic-i4b-confirm-blocked")),
                        candidateId = selfEntryOutcome.newCandidateIds.single(),
                        expectedContentHash = detail.row.contentHash,
                        explicitConfirmedAt = graph.facade.ledgerClock.now().toString(),
                        decisionFields =
                            ImportConfirmDecisionFields.OrdinaryFlow(
                                categoryId = graph.categoryId,
                                fundingAccountId = graph.paymentAccountId,
                            ),
                    ),
                )
            val blockedRejection = assertIs<ImportCandidateDecisionResult.Rejected>(blocked)
            assertEquals("SPINE_DUPLICATE_NOT_CONFIRMABLE", blockedRejection.diagnostic.code)
            // Zero formal effect through the whole leg: the disposal never posts anything.
            assertEquals(0L, formalTransactionCount(graph, driver))
        }
    }
}

/**
 * Minimal zip read/write helpers for the string-edit variant (java.util.zip, the jvmTest
 * shape the desktop toolchain already carries).
 */
private object ZipStreams {
    fun readAll(bytes: ByteArray): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return parts
    }

    fun writeAll(parts: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
