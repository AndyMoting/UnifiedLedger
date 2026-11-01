package com.unifiedledger.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCandidateRejectRequest
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateComparisonFingerprint
import com.unifiedledger.application.ImportDuplicateComparisonSnapshot
import com.unifiedledger.application.ImportDuplicateIntakeIds
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.RejectImportCandidate
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.CatalogBootstrapResult
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.defaultCatalogSeed
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.ledgerStorageLayout
import java.io.File

/** The deterministic 20k-record group for the dedicated scale instrumented modes. */
internal const val SCALE_DUPLICATE_FIXTURE_INPUT_REF = "scale-duplicate-group-v1"
internal const val SCALE_DUPLICATE_FIXTURE_RELATIONS = 20_000

/**
 * The activation intake's own relation count: the CMB activation row carries the same business
 * tuple, so the product matches it against the 20,000 history sources AND the fixture's pending
 * subject (`CmbBillParser.kt:257-302` emits CNY/2/`+08:00`/`out`/`settled`/SETTLED facts).
 */
internal const val SCALE_DUPLICATE_ACTIVATION_RELATIONS = SCALE_DUPLICATE_FIXTURE_RELATIONS + 1

/** The published total after activation: the fixture group plus the activation subject's group. */
internal const val SCALE_DUPLICATE_ACTIVATION_TOTAL_RELATIONS =
    SCALE_DUPLICATE_FIXTURE_RELATIONS + SCALE_DUPLICATE_ACTIVATION_RELATIONS

/**
 * The frozen EXACT_BUSINESS_TUPLE business facts every seeded source must carry. The direction and
 * the instant are the ones the real CMB parser emits (`CmbBillParser.kt:257-302`: `directionToken`
 * is `"out"` for the expense column, `occurredAt` is `<date>T<HH:mm:ss>+08:00`), so the fixture group
 * and the b6 activation row are the same tuple instead of a hand-invented one.
 */
internal const val SCALE_FIXTURE_OCCURRED_AT = "2026-01-02T03:04:05+08:00"
internal const val SCALE_FIXTURE_DIRECTION_TOKEN = "out"
internal const val SCALE_FIXTURE_CURRENCY_CODE = "CNY"
internal const val SCALE_FIXTURE_CURRENCY_PRECISION = 2
internal const val SCALE_FIXTURE_GROUP_AMOUNT_MINOR = 123L
internal const val SCALE_FIXTURE_STATUS_TOKEN = "settled"

private const val SCALE_FIXTURE_LEDGER_ID = PRODUCT_LEDGER_ID
private const val SCALE_FIXTURE_BASELINE_INPUT_REF = "scale-baseline-rejected-v1"
private const val SCALE_FIXTURE_HISTORY_INPUT_REF = "scale-history-settled-v1"
private const val SCALE_FIXTURE_HISTORY_SOURCE_PREFIX = "scale-history-source-"
private const val SCALE_FIXTURE_HISTORY_REQUEST_PREFIX = "scale-history-request-"
private const val SCALE_FIXTURE_GENERATED_AT = "2026-01-02T03:04:06Z"
private const val SCALE_FIXTURE_BASELINE_AMOUNT_MINOR = 456L
private const val SCALE_FIXTURE_BASELINE_SOURCE_ID = "scale-baseline-source"
private const val SCALE_FIXTURE_BASELINE_CANDIDATE_ID = "scale-baseline-candidate"
private const val SCALE_FIXTURE_BASELINE_EVIDENCE_ID = "scale-baseline-evidence"
private const val SCALE_FIXTURE_BASELINE_STATUS_ID = "scale-baseline-status"
private const val SCALE_FIXTURE_BASELINE_REQUEST_ID = "scale-baseline-request"
private const val SCALE_FIXTURE_BASELINE_REJECT_REQUEST_ID = "scale-baseline-reject-request"
private const val SCALE_FIXTURE_BASELINE_REJECT_STATUS_ID = "scale-baseline-reject-status"
internal const val SCALE_FIXTURE_RELATION_SOURCE_ID = "scale-duplicate-source"
private const val SCALE_FIXTURE_RELATION_EVIDENCE_ID = "scale-duplicate-evidence"
internal const val SCALE_FIXTURE_RELATION_CANDIDATE_ID = "scale-duplicate-candidate"
private const val SCALE_FIXTURE_RELATION_STATUS_ID = "scale-duplicate-status"
private const val SCALE_FIXTURE_RELATION_REQUEST_ID = "scale-duplicate-request"
internal const val SCALE_FIXTURE_GENERATION = 1
private const val SCALE_FIXTURE_REAL_REQUEST_COUNT = 3
private const val SCALE_FIXTURE_REAL_RECEIPT_COUNT = 3
private const val SCALE_FIXTURE_COMPLETENESS_TOKEN = "valid_complete"
private const val SCALE_FIXTURE_STAGING_NAME = "ledger.db.scale-fixture-staging"

/**
 * The fixture's read-back proof. Every count is read from the published file, never assumed from
 * what the builder intended to write; [subjectSourceIdCount] is the direct check that all 20,000
 * relations fold back to ONE subject source.
 */
internal data class ScaleDuplicateFixture(
    val generation: Int,
    val inputRef: String,
    val baselineCandidateId: String,
    val candidateId: String,
    val subjectSourceId: String,
    val candidateCount: Int,
    val sourceCount: Int,
    val requestCount: Int,
    val subjectRelationCount: Int,
    val duplicateRelationCount: Int,
    val duplicateHistoryCount: Int,
    val historySourceCount: Int,
    val subjectSourceIdCount: Int,
)

/**
 * D-275/D-276 fixture support. This type deliberately carries NO `@Test`: the only entry point is
 * the `seedFixture` mode on [ImportScaleTraversalInstrumentedTest], which the ordinary 41-case
 * inventory already excludes by class. A test-free builder means the runner can never discover a
 * second, differently-configured seed path.
 *
 * The published library holds exactly three candidate rows: one rejected baseline candidate (its own
 * business tuple, so it never joins the group), 20,000 historical settled source records that are NOT
 * candidates, and one new pending candidate whose single real intake produced 20,000 duplicate
 * relations.
 *
 * Why the 20,000 historical sources exist: [com.unifiedledger.data.SqlDelightImportSpineStore]
 * refuses an intake whose allocated duplicate-id group count differs from the transaction's own
 * `selectDuplicateMatches` result (`SqlDelightImportSpineStore.kt:255-272`). A faked 20,000-relation
 * library cannot come out of one real intake unless 20,000 real, tuple-matching
 * `import_source_record` rows already exist. Those rows are written with the schema's own INSERT
 * columns (one owning `import_request` each, because `UNIQUE (ledger_id, owner_request_id)` binds one
 * source per request) and are identical in shape to what the product's intake writes; no status
 * history, receipt, or append-only guard is bypassed because they carry no candidate.
 *
 * Every candidate, duplicate relation, duplicate status row, evidence row, receipt, and both
 * candidate status histories are created by the real [ExecuteImportIntake]/[RejectImportCandidate]
 * paths — the 20,000 relations are the intake's own output, never inserted by hand.
 */
internal class ImportScaleDuplicateFixtureSupport {
    /**
     * Seeds and publishes the fixture, then reports the readings it read back from the published
     * file. Call it in a process that has not opened the ledger yet: [requireNoOpenLedgerHandle]
     * proves no ledger handle is open here, because replacing the file under a live handle would
     * leave the product writing to the old inode.
     */
    fun seed(
        context: Context,
        log: (String) -> Unit,
    ): ScaleDuplicateFixture {
        val layout = scaleFixtureLayout(context)
        requireNoOpenLedgerHandle(layout, log)
        val cacheDirectory = File(context.cacheDir, "scale-fixture-source")
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "cannot create fixture cache directory" }
        val source = File(cacheDirectory, "duplicate-group.db")
        // Rebuild from the deterministic input every time: a structurally valid but stale or
        // partially-created cache must never silently become this run's measurement fixture.
        buildScaleDuplicateFixture(context, source, log)
        scaleDuplicateFixtureValidation(source)?.let { reason -> error("newly built scale fixture failed validation: $reason") }
        val fileSystem = AndroidLedgerFileSystem()
        val generationDirectory = File(layout.generationDirectory(SCALE_FIXTURE_GENERATION))
        check(generationDirectory.isDirectory || generationDirectory.mkdirs()) { "cannot create generation directory" }
        val main = File(layout.mainFile(generationDirectory.path))
        val pointerText = "gen-$SCALE_FIXTURE_GENERATION"
        val staging = File(generationDirectory, SCALE_FIXTURE_STAGING_NAME)
        deleteFixtureFile(staging)
        try {
            source.inputStream().use { input ->
                staging.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (main.exists() && !main.delete()) error("cannot replace previous fixture database")
            if (!staging.renameTo(main)) error("cannot publish fixture database")
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                deleteFixtureFile(File(main.path + suffix))
            }
            fileSystem.writeAtomic(layout.activePointerFile, pointerText.encodeToByteArray())
            check(!File(layout.switchJournalFile).exists() || File(layout.switchJournalFile).delete()) {
                "cannot clear switch journal before fixture launch"
            }
            val publishedPointer = File(layout.activePointerFile).readText().trim()
            check(publishedPointer == pointerText) { "the active pointer published as '$publishedPointer', not $pointerText" }
            scaleDuplicateFixtureValidation(main)?.let { reason -> error("published scale fixture failed validation: $reason") }
        } catch (failure: Throwable) {
            deleteFixtureFile(staging)
            throw failure
        }
        val fixture = readFixture(main)
        checkFixture(fixture)
        log(
            "SEED_FIXTURE READINGS generation=${fixture.generation} pointer=$pointerText candidates=${fixture.candidateCount} " +
                "sources=${fixture.sourceCount} requests=${fixture.requestCount} historySources=${fixture.historySourceCount} " +
                "relations=${fixture.duplicateRelationCount} subjectRelations=${fixture.subjectRelationCount} " +
                "duplicateHistory=${fixture.duplicateHistoryCount} subjectSources=${fixture.subjectSourceIdCount} quickCheck=ok",
        )
        return fixture
    }

    /**
     * Activation-only path: verifies that an earlier `seedFixture` run left a complete published
     * fixture and returns its readings WITHOUT rebuilding anything, so the b6 mode can never replace
     * the database under a process that already opened it.
     */
    fun requirePublished(
        context: Context,
        log: (String) -> Unit,
    ): ScaleDuplicateFixture {
        val layout = scaleFixtureLayout(context)
        requireNoOpenLedgerHandle(layout, log)
        val pointerText = "gen-$SCALE_FIXTURE_GENERATION"
        val pointer = File(layout.activePointerFile)
        check(pointer.isFile) {
            "no published fixture: run the seedFixture mode first, force-stop the app, then start a fresh process for this mode"
        }
        val publishedPointer = pointer.readText().trim()
        check(publishedPointer == pointerText) {
            "the active pointer is '$publishedPointer', not $pointerText: re-run the seedFixture mode"
        }
        check(!File(layout.switchJournalFile).exists()) { "a switch journal is present; refusing to read a mid-switch fixture" }
        val main = File(layout.mainFile(layout.generationDirectory(SCALE_FIXTURE_GENERATION)))
        scaleDuplicateFixtureValidation(main)?.let { reason ->
            error("the published fixture is invalid ($reason): re-run the seedFixture mode in a fresh process")
        }
        val fixture = readFixture(main)
        checkFixture(fixture)
        log(
            "SEED_FIXTURE REUSED generation=${fixture.generation} candidates=${fixture.candidateCount} sources=${fixture.sourceCount} " +
                "historySources=${fixture.historySourceCount} relations=${fixture.duplicateRelationCount} quickCheck=ok",
        )
        return fixture
    }

    /**
     * The runtime guard both entry points rely on: this process must hold no open handle on the
     * ledger host directory. `/proc/self/fd` is the process's own view, so a handle opened by an
     * earlier Activity in this process (the stale-inode case) shows up here as a live or
     * `(deleted)` path under the generations directory. Nothing else in the mode can prove that, so
     * the check fails loudly instead of replacing a live database file.
     */
    private fun requireNoOpenLedgerHandle(
        layout: LedgerStorageLayout,
        log: (String) -> Unit,
    ) {
        val hostPrefix = File(layout.hostDirectory).canonicalPath + File.separator
        val open =
            File("/proc/self/fd")
                .listFiles()
                .orEmpty()
                .mapNotNull { fd -> runCatching { fd.canonicalPath }.getOrNull() }
                .filter { path ->
                    path.startsWith(hostPrefix) && (path.contains("ledger.db") || path.contains("ledger-generations"))
                }
        check(open.isEmpty()) {
            "this process already holds the ledger database open ($open); replacing it would leave the product writing to the old inode. " +
                "Force-stop the app and run seedFixture in a fresh process."
        }
        log("SEED_FIXTURE GUARD openLedgerHandles=0")
    }

    private fun checkFixture(fixture: ScaleDuplicateFixture) {
        check(fixture.duplicateRelationCount == SCALE_DUPLICATE_FIXTURE_RELATIONS) {
            "published fixture holds ${fixture.duplicateRelationCount} duplicate relations, not $SCALE_DUPLICATE_FIXTURE_RELATIONS"
        }
        check(fixture.subjectRelationCount == SCALE_DUPLICATE_FIXTURE_RELATIONS) { "subject relation count mismatch" }
        check(fixture.subjectSourceIdCount == 1) { "duplicate relations span ${fixture.subjectSourceIdCount} subject sources" }
        check(fixture.historySourceCount == SCALE_DUPLICATE_FIXTURE_RELATIONS) { "history source count mismatch" }
        check(fixture.candidateCount == 2) { "the fixture must publish exactly 2 candidates, found ${fixture.candidateCount}" }
        check(fixture.duplicateHistoryCount == SCALE_DUPLICATE_FIXTURE_RELATIONS) { "duplicate history count mismatch" }
    }

    private fun readFixture(main: File): ScaleDuplicateFixture =
        SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            ScaleDuplicateFixture(
                generation = SCALE_FIXTURE_GENERATION,
                inputRef = SCALE_DUPLICATE_FIXTURE_INPUT_REF,
                baselineCandidateId = SCALE_FIXTURE_BASELINE_CANDIDATE_ID,
                candidateId = SCALE_FIXTURE_RELATION_CANDIDATE_ID,
                subjectSourceId = SCALE_FIXTURE_RELATION_SOURCE_ID,
                candidateCount = scalarLong(database, "SELECT COUNT(*) FROM import_candidate WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID).toInt(),
                sourceCount = scalarLong(database, "SELECT COUNT(*) FROM import_source_record WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID).toInt(),
                requestCount = scalarLong(database, "SELECT COUNT(*) FROM import_request WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID).toInt(),
                subjectRelationCount =
                    scalarLong(
                        database,
                        "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=? AND subject_source_id=?",
                        SCALE_FIXTURE_LEDGER_ID,
                        SCALE_FIXTURE_RELATION_SOURCE_ID,
                    ).toInt(),
                duplicateRelationCount =
                    scalarLong(database, "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID).toInt(),
                duplicateHistoryCount =
                    scalarLong(database, "SELECT COUNT(*) FROM import_duplicate_status_history WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID).toInt(),
                historySourceCount =
                    scalarLong(
                        database,
                        "SELECT COUNT(*) FROM import_source_record WHERE ledger_id=? AND source_id LIKE ?",
                        SCALE_FIXTURE_LEDGER_ID,
                        "$SCALE_FIXTURE_HISTORY_SOURCE_PREFIX%",
                    ).toInt(),
                subjectSourceIdCount =
                    scalarLong(
                        database,
                        "SELECT COUNT(DISTINCT subject_source_id) FROM import_duplicate_candidate WHERE ledger_id=?",
                        SCALE_FIXTURE_LEDGER_ID,
                    ).toInt(),
            )
        }
}

internal fun scaleFixtureLayout(context: Context): LedgerStorageLayout {
    val (hostDirectory, _) = androidStableStoragePaths(context.getDatabasePath("ledger.db"))
    return ledgerStorageLayout(AndroidLedgerFileSystem(), hostDirectory)
}

/**
 * Validates one database file and returns the FIRST failure as a readable reason (null = valid). The
 * reason is carried into the mode's evidence, so a rejected fixture says why instead of only that it
 * failed.
 */
internal fun scaleDuplicateFixtureValidation(databaseFile: File): String? {
    if (!databaseFile.isFile) return "${databaseFile.name} is not a readable file"
    return runCatching {
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            validateDatabase(database)
        }
    }.getOrElse { failure -> "${failure::class.simpleName}: ${failure.message}" }
}

@Suppress("LongMethod")
private fun validateDatabase(database: SQLiteDatabase): String? {
    fun expect(
        valid: Boolean,
        reason: () -> String,
    ): String? = if (valid) null else reason()

    expect(database.rawQuery("PRAGMA quick_check", null).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "ok" }) {
        "PRAGMA quick_check did not return ok"
    }?.let { return it }
    val foreignKeyErrors =
        database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    expect(foreignKeyErrors.isEmpty()) { "foreign_key_check=$foreignKeyErrors" }?.let { return it }
    // Every source row owns a request row (UNIQUE(ledger_id, owner_request_id)).
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_source_record s LEFT JOIN import_request r " +
                "ON r.ledger_id=s.ledger_id AND r.request_id=s.owner_request_id " +
                "WHERE s.ledger_id=? AND r.request_id IS NULL",
            SCALE_FIXTURE_LEDGER_ID,
        ) == 0L,
    ) { "a source row has no owning request row" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_candidate d " +
                "LEFT JOIN import_candidate c ON c.ledger_id=d.ledger_id AND c.source_id=d.subject_source_id " +
                "WHERE c.candidate_id IS NULL",
        ) == 0L,
    ) { "duplicate -> candidate orphan rows exist" }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM import_candidate WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID) == 2L) {
        "import_candidate count is not 2"
    }?.let { return it }
    expect(
        scalarLong(database, "SELECT COUNT(*) FROM import_source_record WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID) ==
            (SCALE_DUPLICATE_FIXTURE_RELATIONS + 2).toLong(),
    ) { "import_source_record count is not ${SCALE_DUPLICATE_FIXTURE_RELATIONS + 2}" }?.let { return it }
    expect(
        scalarLong(database, "SELECT COUNT(*) FROM import_request WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID) ==
            (SCALE_DUPLICATE_FIXTURE_RELATIONS + SCALE_FIXTURE_REAL_REQUEST_COUNT).toLong(),
    ) { "import_request count is not ${SCALE_DUPLICATE_FIXTURE_RELATIONS + SCALE_FIXTURE_REAL_REQUEST_COUNT}" }?.let { return it }
    // Receipts exist only for the three real requests; the 20,000 history requests are fabricated
    // rows and must never carry a fake intake receipt.
    expect(
        scalarLong(database, "SELECT COUNT(*) FROM import_receipt WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID) ==
            SCALE_FIXTURE_REAL_RECEIPT_COUNT.toLong(),
    ) { "import_receipt count is not $SCALE_FIXTURE_REAL_RECEIPT_COUNT" }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM ledger_transaction WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID) == 0L) {
        "the fixture must not create a formal transaction"
    }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM import_confirmation WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID) == 0L) {
        "the fixture must not create a confirmation"
    }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl='import_duplicate_candidate'") > 0L) {
        "no sqlite_stat1 row for import_duplicate_candidate (ANALYZE did not run)"
    }?.let { return it }
    // The 20,000 pre-existing sources are real tuple matches: same ledger, the frozen
    // EXACT_BUSINESS_TUPLE business metadata, distinct source ids and ordinals.
    val ordinaryContractVersion = ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion.toLong().toString()
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_source_record WHERE ledger_id=? AND source_id LIKE ? " +
                "AND record_kind=? AND contract_version=? AND completeness=? AND amount_minor=? " +
                "AND currency_code=? AND currency_precision=? AND occurred_at=? AND direction_token=? " +
                "AND status_token=? AND funding_state=?",
            SCALE_FIXTURE_LEDGER_ID,
            "$SCALE_FIXTURE_HISTORY_SOURCE_PREFIX%",
            ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue,
            ordinaryContractVersion,
            SCALE_FIXTURE_COMPLETENESS_TOKEN,
            SCALE_FIXTURE_GROUP_AMOUNT_MINOR.toString(),
            SCALE_FIXTURE_CURRENCY_CODE,
            SCALE_FIXTURE_CURRENCY_PRECISION.toString(),
            SCALE_FIXTURE_OCCURRED_AT,
            SCALE_FIXTURE_DIRECTION_TOKEN,
            SCALE_FIXTURE_STATUS_TOKEN,
            ImportFundingState.SETTLED.name,
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "the history sources do not carry the frozen business tuple" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(DISTINCT source_id) FROM import_source_record WHERE ledger_id=? AND source_id LIKE ?",
            SCALE_FIXTURE_LEDGER_ID,
            "$SCALE_FIXTURE_HISTORY_SOURCE_PREFIX%",
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "the history source ids are not distinct" }?.let { return it }
    // The 20,000 duplicate relations: one subject (the new pending candidate's source), 20,000
    // distinct EXACT_BUSINESS_TUPLE targets inside the history set, zero targets outside it.
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=? AND kind='EXACT_BUSINESS_TUPLE'",
            SCALE_FIXTURE_LEDGER_ID,
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "EXACT_BUSINESS_TUPLE relation count is not $SCALE_DUPLICATE_FIXTURE_RELATIONS" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=? AND subject_source_id=?",
            SCALE_FIXTURE_LEDGER_ID,
            SCALE_FIXTURE_RELATION_SOURCE_ID,
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "not every duplicate relation belongs to the fixture subject" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(DISTINCT subject_source_id) FROM import_duplicate_candidate WHERE ledger_id=?",
            SCALE_FIXTURE_LEDGER_ID,
        ) == 1L,
    ) { "the duplicate relations use more than one subject source" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=? AND possible_existing_source_id LIKE ?",
            SCALE_FIXTURE_LEDGER_ID,
            "$SCALE_FIXTURE_HISTORY_SOURCE_PREFIX%",
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "the duplicate relations do not all target history sources" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(DISTINCT possible_existing_source_id) FROM import_duplicate_candidate WHERE ledger_id=?",
            SCALE_FIXTURE_LEDGER_ID,
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "the duplicate relation targets are not distinct" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(DISTINCT comparison_snapshot) FROM import_duplicate_candidate WHERE ledger_id=?",
            SCALE_FIXTURE_LEDGER_ID,
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "the duplicate comparison snapshots are not per-relation" }?.let { return it }
    val sampleComparison =
        ImportDuplicateComparisonSnapshot(
            subjectSourceId = ImportSourceId(SCALE_FIXTURE_RELATION_SOURCE_ID),
            possibleExistingSourceId = ImportSourceId(historySourceId(0)),
            recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
            contractVersion = 1,
            amountMinor = SCALE_FIXTURE_GROUP_AMOUNT_MINOR,
            currencyCode = SCALE_FIXTURE_CURRENCY_CODE,
            currencyPrecision = SCALE_FIXTURE_CURRENCY_PRECISION,
            occurredAt = SCALE_FIXTURE_OCCURRED_AT,
            directionToken = SCALE_FIXTURE_DIRECTION_TOKEN,
            statusToken = SCALE_FIXTURE_STATUS_TOKEN,
        )
    val sampleTuple = ImportDuplicateComparisonFingerprint().canonicalJson(sampleComparison)
    val expectedFingerprint = ImportDuplicateComparisonFingerprint().digest(sampleComparison)
    // The product fingerprint is the frozen BUSINESS-TUPLE digest only
    // (`ImportContentFingerprint.kt:67-81` hashes amount/contract-version/currency/precision/direction/
    // occurred-at/record-kind/status — no subject id, no possible-existing id), so the 20,000 relations
    // of one tuple necessarily carry ONE fingerprint. The fingerprint is therefore NOT the relation
    // identity: identity is the `(ledger, subject, possible_existing, kind, fingerprint)` unique index
    // plus the per-relation `comparison_snapshot`, which the distinct-snapshot assertion above and the
    // exact sample row below prove. A per-relation fingerprint would be a digest the product never
    // writes, so asserting it would make this publication check impossible by construction.
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=? AND comparison_fingerprint=?",
            SCALE_FIXTURE_LEDGER_ID,
            expectedFingerprint,
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "the 20,000 relations do not all carry the one frozen business-tuple fingerprint" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(DISTINCT comparison_fingerprint) FROM import_duplicate_candidate WHERE ledger_id=?",
            SCALE_FIXTURE_LEDGER_ID,
        ) == 1L,
    ) { "the relations carry more than one business-tuple fingerprint" }?.let { return it }
    val sampleSnapshot =
        "{\"possible_existing_source_id\":\"${historySourceId(0)}\",\"subject_source_id\":\"$SCALE_FIXTURE_RELATION_SOURCE_ID\",\"tuple\":$sampleTuple}"
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=? AND possible_existing_source_id=? " +
                "AND comparison_fingerprint=? AND comparison_snapshot=?",
            SCALE_FIXTURE_LEDGER_ID,
            historySourceId(0),
            expectedFingerprint,
            sampleSnapshot,
        ) == 1L,
    ) { "the duplicate projection does not match the product's own frozen comparison" }?.let { return it }
    expect(
        scalarLong(database, "SELECT COUNT(*) FROM import_duplicate_status_history WHERE ledger_id=?", SCALE_FIXTURE_LEDGER_ID) ==
            SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "import_duplicate_status_history count is not $SCALE_DUPLICATE_FIXTURE_RELATIONS" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_status_history WHERE ledger_id=? AND (status!='DEFERRED' OR operation_class!='creation' OR sequence!=1)",
            SCALE_FIXTURE_LEDGER_ID,
        ) == 0L,
    ) { "a duplicate status history row is not a creation DEFERRED row at sequence 1" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_candidate_status_history h " +
                "JOIN import_candidate c ON c.ledger_id=h.ledger_id AND c.candidate_id=h.candidate_id " +
                "WHERE c.candidate_id=? AND h.sequence=1 AND h.status='pending_confirmation'",
            SCALE_FIXTURE_RELATION_CANDIDATE_ID,
        ) == 1L,
    ) { "the fixture subject candidate is not pending_confirmation at sequence 1" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_candidate_status_history h WHERE h.candidate_id=? AND h.sequence=2 AND h.status='rejected'",
            SCALE_FIXTURE_BASELINE_CANDIDATE_ID,
        ) == 1L,
    ) { "the baseline candidate is not rejected at sequence 2" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_duplicate_candidate d JOIN import_source_record s " +
                "ON s.ledger_id=d.ledger_id AND s.source_id=d.subject_source_id " +
                "WHERE s.input_ref=? AND d.kind='EXACT_BUSINESS_TUPLE'",
            SCALE_DUPLICATE_FIXTURE_INPUT_REF,
        ) == SCALE_DUPLICATE_FIXTURE_RELATIONS.toLong(),
    ) { "the fixture subject's input_ref does not carry all relations" }?.let { return it }
    return null
}

private fun historySourceId(index: Int): String = SCALE_FIXTURE_HISTORY_SOURCE_PREFIX + index.toString().padStart(5, '0')

private fun historyRequestId(index: Int): String = SCALE_FIXTURE_HISTORY_REQUEST_PREFIX + index.toString().padStart(5, '0')

private fun buildScaleDuplicateFixture(
    context: Context,
    databaseFile: File,
    log: (String) -> Unit,
) {
    deleteFixtureFile(databaseFile)
    createAndroidLedgerDatabase(context, databaseFile.path).use { handle ->
        val ledgerId = LedgerId(SCALE_FIXTURE_LEDGER_ID)
        check(handle.catalogStore.bootstrap(ledgerId, defaultCatalogSeed()) !is CatalogBootstrapResult.UnknownReference)
        // The library artifact: 20,000 settled sources with the frozen business tuple, each with its
        // own owning request. Written before the group intake so that intake's own
        // `selectDuplicateMatches` count — not this fixture's intent — determines the relation count.
        seedHistoricalSettledSources(handle)
        log("SEED_FIXTURE build historySources=$SCALE_DUPLICATE_FIXTURE_RELATIONS")
        val baselineIds =
            fixtureIdSource(
                sourceId = SCALE_FIXTURE_BASELINE_SOURCE_ID,
                evidenceId = SCALE_FIXTURE_BASELINE_EVIDENCE_ID,
                candidateId = SCALE_FIXTURE_BASELINE_CANDIDATE_ID,
                statusHistoryId = SCALE_FIXTURE_BASELINE_STATUS_ID,
                expectedDuplicateIds = 0,
            )
        val baseline =
            acceptedIntake(
                ExecuteImportIntake(handle.importSpineStore, baselineIds, ImportContentFingerprint()),
                request =
                    ImportIntakeRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId(SCALE_FIXTURE_BASELINE_REQUEST_ID)),
                        inputRef = SCALE_FIXTURE_BASELINE_INPUT_REF,
                        recordOrdinal = 0,
                        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                        // A DIFFERENT business tuple: the rejected baseline is a visible,
                        // non-pending candidate and can never become a 20,001st group member.
                        facts = scaleFixtureFacts(amountMinor = SCALE_FIXTURE_BASELINE_AMOUNT_MINOR),
                        completeness = ImportCompleteness.VALID_COMPLETE,
                        candidateGeneratedAt = SCALE_FIXTURE_GENERATED_AT,
                    ),
                expectedCandidateId = SCALE_FIXTURE_BASELINE_CANDIDATE_ID,
            )
        val rejected =
            RejectImportCandidate(handle.importSpineStore) { ImportStatusHistoryId(SCALE_FIXTURE_BASELINE_REJECT_STATUS_ID) }
                .execute(
                    ImportCandidateRejectRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId(SCALE_FIXTURE_BASELINE_REJECT_REQUEST_ID)),
                        candidateId = baseline.receipt.candidateId,
                        expectedContentHash =
                            ImportContentFingerprint()
                                .digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, scaleFixtureFacts(SCALE_FIXTURE_BASELINE_AMOUNT_MINOR)),
                    ),
                )
        check(rejected is ImportCandidateDecisionResult.Accepted) { "baseline candidate was not rejected: $rejected" }
        val groupIds =
            fixtureIdSource(
                sourceId = SCALE_FIXTURE_RELATION_SOURCE_ID,
                evidenceId = SCALE_FIXTURE_RELATION_EVIDENCE_ID,
                candidateId = SCALE_FIXTURE_RELATION_CANDIDATE_ID,
                statusHistoryId = SCALE_FIXTURE_RELATION_STATUS_ID,
                expectedDuplicateIds = SCALE_DUPLICATE_FIXTURE_RELATIONS,
            )
        // The real intake: its transaction-internal match count is the demand handed to the id
        // source, so the 20,000 relations below are the store's own output.
        acceptedIntake(
            ExecuteImportIntake(handle.importSpineStore, groupIds, ImportContentFingerprint()),
            request =
                ImportIntakeRequest(
                    identity = ImportRequestIdentity(ledgerId, ImportRequestId(SCALE_FIXTURE_RELATION_REQUEST_ID)),
                    inputRef = SCALE_DUPLICATE_FIXTURE_INPUT_REF,
                    recordOrdinal = 0,
                    recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    facts = scaleFixtureFacts(amountMinor = SCALE_FIXTURE_GROUP_AMOUNT_MINOR),
                    completeness = ImportCompleteness.VALID_COMPLETE,
                    candidateGeneratedAt = SCALE_FIXTURE_GENERATED_AT,
                ),
            expectedCandidateId = SCALE_FIXTURE_RELATION_CANDIDATE_ID,
        )
        check(baselineIds.requiredDuplicateIds == 0) {
            "the rejected baseline matched ${baselineIds.requiredDuplicateIds} existing sources; it must stay outside the group"
        }
        check(groupIds.requiredDuplicateIds == SCALE_DUPLICATE_FIXTURE_RELATIONS) {
            "the group intake matched ${groupIds.requiredDuplicateIds} existing sources, not $SCALE_DUPLICATE_FIXTURE_RELATIONS"
        }
        // The production handle's intake-completion statistics step; no fixture-owned DDL/index.
        handle.runFullAnalyze()
    }
}

/**
 * Writes the 20,000 pre-existing settled sources through the handle's own generated statements, in
 * one transaction. Only `import_request` + `import_source_record` are written here — the same two
 * tables, the same columns, and the same token vocabulary the product's intake uses — so every row
 * passes the schema's FK, uniqueness, and business-tuple CHECKs. No candidate, status history,
 * receipt, or duplicate row is fabricated; those all come from the real intake.
 */
private fun seedHistoricalSettledSources(handle: AndroidLedgerDatabaseHandle) {
    val facts = scaleFixtureFacts(amountMinor = SCALE_FIXTURE_GROUP_AMOUNT_MINOR)
    val contentHash = ImportContentFingerprint().digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, facts)
    handle.database.transaction {
        repeat(SCALE_DUPLICATE_FIXTURE_RELATIONS) { index ->
            val requestId = historyRequestId(index)
            handle.database.ledgerQueries.claimImportRequest(
                SCALE_FIXTURE_LEDGER_ID,
                requestId,
                "intake",
            )
            handle.database.ledgerQueries.insertImportSourceRecord(
                ledger_id = SCALE_FIXTURE_LEDGER_ID,
                source_id = historySourceId(index),
                owner_request_id = requestId,
                input_ref = SCALE_FIXTURE_HISTORY_INPUT_REF,
                record_ordinal = index.toLong(),
                record_kind = ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue,
                content_hash = contentHash,
                contract_version = ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion.toLong(),
                completeness = SCALE_FIXTURE_COMPLETENESS_TOKEN,
                amount_minor = facts.amountMinor,
                currency_code = facts.currencyCode,
                currency_precision = facts.currencyPrecision.toLong(),
                occurred_at = facts.occurredAt,
                direction_token = facts.directionToken,
                status_token = facts.statusToken,
                funding_state = facts.fundingState.name,
                funding_rule_id = facts.fundingRuleId,
                funding_rule_version = facts.fundingRuleVersion.toLong(),
                candidate_generated_at = SCALE_FIXTURE_GENERATED_AT,
            )
        }
    }
}

private fun fixtureIdSource(
    sourceId: String,
    evidenceId: String,
    candidateId: String,
    statusHistoryId: String,
    expectedDuplicateIds: Int,
): ScaleFixtureIdSource =
    ScaleFixtureIdSource(
        sourceId = sourceId,
        evidenceId = evidenceId,
        candidateId = candidateId,
        statusHistoryId = statusHistoryId,
        expectedDuplicateIds = expectedDuplicateIds,
    )

private fun acceptedIntake(
    intake: ExecuteImportIntake,
    request: ImportIntakeRequest,
    expectedCandidateId: String,
): ImportIntakeResult.Accepted {
    val result = intake.execute(request)
    check(result is ImportIntakeResult.Accepted) { "fixture intake rejected: $result" }
    check(result.receipt.candidateId.value == expectedCandidateId)
    return result
}

private fun scaleFixtureFacts(amountMinor: Long) =
    ImportSourceFacts(
        amountMinor = amountMinor,
        currencyCode = SCALE_FIXTURE_CURRENCY_CODE,
        currencyPrecision = SCALE_FIXTURE_CURRENCY_PRECISION,
        occurredAt = SCALE_FIXTURE_OCCURRED_AT,
        directionToken = SCALE_FIXTURE_DIRECTION_TOKEN,
        statusToken = SCALE_FIXTURE_STATUS_TOKEN,
        fundingState = ImportFundingState.SETTLED,
        fundingRuleId = "scale-fixture-settled-v1",
        fundingRuleVersion = 1,
    )

/**
 * One fixture-owned id source, one per intake. [next] records the demand the store's winning
 * transaction computed and refuses anything but the expected count, so a fixture that no longer
 * matches its declared structure fails at allocation time instead of publishing a thin library.
 */
private class ScaleFixtureIdSource(
    private val sourceId: String,
    private val evidenceId: String,
    private val candidateId: String,
    private val statusHistoryId: String,
    private val expectedDuplicateIds: Int,
) : ImportIntakeIdSource {
    var requiredDuplicateIds: Int = -1
        private set

    override fun next(requiredDuplicateIds: Int): ImportIntakeIds {
        this.requiredDuplicateIds = requiredDuplicateIds
        require(requiredDuplicateIds == expectedDuplicateIds) {
            "intake demanded $requiredDuplicateIds duplicate ids; the fixture requires $expectedDuplicateIds"
        }
        return ImportIntakeIds(
            sourceId = ImportSourceId(sourceId),
            evidenceId = ImportEvidenceId(evidenceId),
            candidateId = ImportCandidateId(candidateId),
            statusHistoryId = ImportStatusHistoryId(statusHistoryId),
            duplicateIds =
                List(requiredDuplicateIds) { index ->
                    val suffix = index.toString().padStart(5, '0')
                    ImportDuplicateIntakeIds(
                        candidateId = ImportDuplicateCandidateId("$candidateId-duplicate-$suffix"),
                        statusHistoryId = ImportStatusHistoryId("$candidateId-duplicate-history-$suffix"),
                    )
                },
        )
    }
}

private fun scalarLong(
    database: SQLiteDatabase,
    sql: String,
    vararg args: String,
): Long =
    database.rawQuery(sql, args.toList().toTypedArray()).use { cursor ->
        check(cursor.moveToFirst()) { "no scalar result for $sql" }
        cursor.getLong(0)
    }

private fun deleteFixtureFile(file: File) {
    if (file.exists() && !file.delete()) error("cannot delete fixture-owned file ${file.name}")
}
