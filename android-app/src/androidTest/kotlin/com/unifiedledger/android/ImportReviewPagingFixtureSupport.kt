package com.unifiedledger.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
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
import com.unifiedledger.data.CatalogBootstrapResult
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.defaultCatalogSeed
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.LedgerStorageLayout
import java.io.File

/**
 * The candidate library the page-crossing test publishes: three whole pages at
 * [IMPORT_REVIEW_PAGE_SIZE]. The whole-page size is a harness requirement, not a product one: the
 * D-349 prefix walk (`AndroidScaleUi.collectTraversal`) takes a target expressed in whole pages —
 * since D-352 it loads `targetRows / IMPORT_REVIEW_PAGE_ROWS` pages up front through the next-page
 * affordance and then walks that static list once — so a library that ends mid-page can only be
 * walked as far as its last whole page.
 */
internal const val IMPORT_REVIEW_PAGING_CANDIDATES = 150

/**
 * The product's review-list page size (`P503App.kt:5966`). Duplicated here as the test's own
 * expectation rather than read back from the product: a silent change to the page size must make
 * this test's three-page shape fail loudly, not follow the product to a different shape.
 */
internal const val IMPORT_REVIEW_PAGING_PAGE_SIZE = 50

/** The fixture's first candidate amount in exact minor units (100.00 CNY); each row adds one. */
private const val IMPORT_REVIEW_PAGING_FIRST_AMOUNT_MINOR = 10_000L

private const val IMPORT_REVIEW_PAGING_INPUT_REF = "import-review-paging-v1"
private const val IMPORT_REVIEW_PAGING_ID_PREFIX = "review-paging-"
private const val IMPORT_REVIEW_PAGING_CURRENCY_CODE = "CNY"
private const val IMPORT_REVIEW_PAGING_CURRENCY_PRECISION = 2
private const val IMPORT_REVIEW_PAGING_OCCURRED_AT = "2026-01-02T03:04:05+08:00"
private const val IMPORT_REVIEW_PAGING_DIRECTION_TOKEN = "out"
private const val IMPORT_REVIEW_PAGING_STATUS_TOKEN = "settled"
private const val IMPORT_REVIEW_PAGING_GENERATED_AT = "2026-01-02T03:04:06Z"
private const val IMPORT_REVIEW_PAGING_COMPLETENESS_TOKEN = "valid_complete"
private const val IMPORT_REVIEW_PAGING_FUNDING_RULE_ID = "import-review-paging-settled-v1"
private const val IMPORT_REVIEW_PAGING_FUNDING_RULE_VERSION = 1
private const val IMPORT_REVIEW_PAGING_GENERATION = 1
private const val IMPORT_REVIEW_PAGING_LEDGER_ID = PRODUCT_LEDGER_ID
private const val IMPORT_REVIEW_PAGING_SOURCE_DIRECTORY = "import-review-paging-source"
private const val IMPORT_REVIEW_PAGING_SOURCE_DATABASE = "import-review-paging.db"
private const val IMPORT_REVIEW_PAGING_STAGING_NAME = "ledger.db.import-review-paging-staging"
private const val IMPORT_REVIEW_PAGING_PENDING_STATUS = "pending_confirmation"

/**
 * The published library's own reading, taken from the file the app will open. [amountsMinor] is in
 * the review list's order — the store's keyset order on `candidate_id` (`candidate_id ASC`,
 * `SqlDelightImportReviewReadAdapter.loadImportReviewPage`) — so the test's expected page
 * boundaries are derived from this list, never from a row index.
 *
 * [kindLine] is the kind half of the render signature the harness compares against
 * (`AndroidScaleUi.signatureOrNull` builds `amount|kindLine`): every row of this library renders the
 * same one, because the fixture seeds one record kind, one occurrence time, one direction/status and
 * no duplicate group.
 */
internal data class ImportReviewPagingFixture(
    val generation: Int,
    val candidateCount: Int,
    val candidateIds: List<String>,
    val amountsMinor: List<Long>,
    val kindLine: String,
)

/**
 * Seeds the page-crossing candidate library on a device and publishes it where the app opens its
 * ledger. It is the sibling of [ImportScaleDuplicateFixtureSupport] and follows the same path on
 * purpose: the REAL [ExecuteImportIntake] over the production database handle builds every
 * candidate (no row is inserted by hand), then the built file is published into generation 1 with
 * the active-pointer discipline the product's own switch uses.
 *
 * Why it is a sibling instead of a parameter on that fixture: that fixture's acceptance is the
 * 20k duplicate group — its validator pins exactly two candidates, `relations + 2` sources, three
 * real requests/receipts and the `sqlite_stat1` row the rig reads. A parameter there would make
 * every one of those pinned numbers conditional on a flag the rig never passes; a separate
 * builder keeps the scale fixture's frozen shape untouched.
 *
 * Reused from that fixture: [scaleFixtureLayout] (the same storage-layout resolution), the same
 * catalog bootstrap, the same real intake, and the same staging-copy → `fsync` → rename →
 * active-pointer publication, copied because the publication block is private to its `seed`.
 */
internal class ImportReviewPagingFixtureSupport {
    /**
     * Seeds, validates, publishes and re-reads the library. Call it in a process that has not
     * opened the ledger yet: [requireNoOpenLedgerHandle] proves that, because replacing the file
     * under a live handle would leave the product writing to the old inode.
     */
    fun seed(
        context: Context,
        log: (String) -> Unit,
        candidates: Int = IMPORT_REVIEW_PAGING_CANDIDATES,
    ): ImportReviewPagingFixture {
        check(candidates > 0) { "the candidate library must hold at least one candidate, requested $candidates" }
        val layout = scaleFixtureLayout(context)
        requireNoOpenLedgerHandle(layout, log)
        val sourceDirectory = File(context.cacheDir, IMPORT_REVIEW_PAGING_SOURCE_DIRECTORY)
        check(sourceDirectory.isDirectory || sourceDirectory.mkdirs()) { "cannot create the fixture cache directory" }
        val source = File(sourceDirectory, IMPORT_REVIEW_PAGING_SOURCE_DATABASE)
        // Rebuilt from the deterministic input every run: a stale but structurally valid cache must
        // never become this run's page-boundary measurement.
        buildLibrary(context, source, log, candidates)
        importReviewPagingValidation(source, candidates)?.let { reason -> error("the newly built page library failed validation: $reason") }
        val fileSystem = AndroidLedgerFileSystem()
        val generationDirectory = File(layout.generationDirectory(IMPORT_REVIEW_PAGING_GENERATION))
        check(generationDirectory.isDirectory || generationDirectory.mkdirs()) { "cannot create the generation directory" }
        val main = File(layout.mainFile(generationDirectory.path))
        val pointerText = "gen-$IMPORT_REVIEW_PAGING_GENERATION"
        val staging = File(generationDirectory, IMPORT_REVIEW_PAGING_STAGING_NAME)
        deleteFixtureFile(staging)
        try {
            source.inputStream().use { input ->
                staging.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (main.exists() && !main.delete()) error("cannot replace the previous page library")
            if (!staging.renameTo(main)) error("cannot publish the page library")
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                deleteFixtureFile(File(main.path + suffix))
            }
            fileSystem.writeAtomic(layout.activePointerFile, pointerText.encodeToByteArray())
            check(!File(layout.switchJournalFile).exists() || File(layout.switchJournalFile).delete()) {
                "cannot clear the switch journal before the fixture launch"
            }
            val publishedPointer = File(layout.activePointerFile).readText().trim()
            check(publishedPointer == pointerText) { "the active pointer published as '$publishedPointer', not $pointerText" }
            importReviewPagingValidation(main, candidates)?.let { reason -> error("the published page library failed validation: $reason") }
        } catch (failure: Throwable) {
            deleteFixtureFile(staging)
            throw failure
        }
        val fixture = readFixture(main, candidates)
        checkFixture(fixture, candidates)
        log(
            "SEED_REVIEW_PAGING READINGS generation=${fixture.generation} pointer=$pointerText " +
                "candidates=${fixture.candidateCount} pages=${importReviewPagingPageCount(candidates)} first=${fixture.amountsMinor.first()} " +
                "last=${fixture.amountsMinor.last()} order=keyset quickCheck=ok",
        )
        return fixture
    }

    /**
     * The runtime guard: this process must hold no open handle on the ledger host directory, so a
     * `MainActivity` from an earlier case in the same process can never be publishing underneath
     * this one. `/proc/self/fd` is the process's own view of its descriptors.
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
                .mapNotNull { descriptor -> runCatching { descriptor.canonicalPath }.getOrNull() }
                .filter { path -> path.startsWith(hostPrefix) && (path.contains("ledger.db") || path.contains("ledger-generations")) }
        check(open.isEmpty()) {
            "this process already holds the ledger database open ($open); replacing it would leave the product " +
                "writing to the old inode, so this case must not share a process with an earlier app launch"
        }
        log("SEED_REVIEW_PAGING GUARD openLedgerHandles=0")
    }

    private fun checkFixture(
        fixture: ImportReviewPagingFixture,
        candidates: Int,
    ) {
        check(fixture.candidateCount == candidates) {
            "the published library holds ${fixture.candidateCount} candidates, not $candidates"
        }
        check(fixture.generation == IMPORT_REVIEW_PAGING_GENERATION) { "the published generation is ${fixture.generation}" }
        check(fixture.candidateIds == fixture.candidateIds.sorted()) {
            "the fixture's candidate ids are not in the store's keyset order, so the expected page order would lie"
        }
        check(fixture.amountsMinor.distinct().size == candidates) { "the fixture's rendered amounts are not unique" }
        check(fixture.kindLine.isNotBlank()) { "the fixture's rendered kind line is blank" }
        check(fixture.candidateCount % IMPORT_REVIEW_PAGING_PAGE_SIZE == 0) {
            "the library must be whole pages for the prefix walk, but ${fixture.candidateCount} rows leave a short last page"
        }
    }
}

/**
 * Builds one candidate per [candidates], all pending and all with a distinct business tuple, through
 * the real intake in one production handle. Distinct amounts are what keep every row in the
 * ordinary user-decision class: the intake's own duplicate-match count is zero for each, so no row
 * folds into a 疑似重复 group and the review list keeps one group of [candidates] rows.
 */
private fun buildLibrary(
    context: Context,
    databaseFile: File,
    log: (String) -> Unit,
    candidates: Int,
) {
    deleteFixtureFile(databaseFile)
    createAndroidLedgerDatabase(context, databaseFile.path).use { handle ->
        val ledgerId = LedgerId(IMPORT_REVIEW_PAGING_LEDGER_ID)
        check(handle.catalogStore.bootstrap(ledgerId, defaultCatalogSeed()) !is CatalogBootstrapResult.UnknownReference)
        repeat(candidates) { index ->
            val suffix = index.toString().padStart(4, '0')
            val ids =
                ImportIntakeIds(
                    sourceId = ImportSourceId(IMPORT_REVIEW_PAGING_ID_PREFIX + "source-" + suffix),
                    evidenceId = ImportEvidenceId(IMPORT_REVIEW_PAGING_ID_PREFIX + "evidence-" + suffix),
                    candidateId = ImportCandidateId(IMPORT_REVIEW_PAGING_ID_PREFIX + "candidate-" + suffix),
                    statusHistoryId = ImportStatusHistoryId(IMPORT_REVIEW_PAGING_ID_PREFIX + "status-" + suffix),
                )
            val idSource = SingleCandidateIdSource(ids)
            val result =
                ExecuteImportIntake(handle.importSpineStore, idSource, ImportContentFingerprint())
                    .execute(
                        ImportIntakeRequest(
                            identity = ImportRequestIdentity(ledgerId, ImportRequestId(IMPORT_REVIEW_PAGING_ID_PREFIX + "request-" + suffix)),
                            inputRef = IMPORT_REVIEW_PAGING_INPUT_REF,
                            recordOrdinal = index,
                            recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                            facts = pagingFacts(IMPORT_REVIEW_PAGING_FIRST_AMOUNT_MINOR + index),
                            completeness = ImportCompleteness.VALID_COMPLETE,
                            candidateGeneratedAt = IMPORT_REVIEW_PAGING_GENERATED_AT,
                        ),
                    )
            check(result is ImportIntakeResult.Accepted) { "fixture intake $index was not accepted: $result" }
            check(result.receipt.candidateId.value == ids.candidateId.value) {
                "fixture intake $index landed ${result.receipt.candidateId.value}, not ${ids.candidateId.value}"
            }
            check(idSource.requiredDuplicateIds == 0) {
                "fixture intake $index matched ${idSource.requiredDuplicateIds} existing sources; the library must " +
                    "hold no duplicate group, because a 疑似重复 fold would split the list's single class group"
            }
        }
        // The production handle's intake-completion statistics step; no fixture-owned DDL or index.
        handle.runFullAnalyze()
        log("SEED_REVIEW_PAGING build candidates=$candidates amounts=${IMPORT_REVIEW_PAGING_FIRST_AMOUNT_MINOR}..${IMPORT_REVIEW_PAGING_FIRST_AMOUNT_MINOR + candidates - 1}")
    }
}

/** One fixture-owned id source per intake: the store's own demand must be zero matches, never assumed. */
private class SingleCandidateIdSource(
    private val ids: ImportIntakeIds,
) : ImportIntakeIdSource {
    var requiredDuplicateIds: Int = -1
        private set

    override fun next(requiredDuplicateIds: Int): ImportIntakeIds {
        this.requiredDuplicateIds = requiredDuplicateIds
        require(requiredDuplicateIds == 0) {
            "intake demanded $requiredDuplicateIds duplicate ids; the page library must hold no duplicate group"
        }
        return ids
    }
}

private fun pagingFacts(amountMinor: Long) =
    ImportSourceFacts(
        amountMinor = amountMinor,
        currencyCode = IMPORT_REVIEW_PAGING_CURRENCY_CODE,
        currencyPrecision = IMPORT_REVIEW_PAGING_CURRENCY_PRECISION,
        occurredAt = IMPORT_REVIEW_PAGING_OCCURRED_AT,
        directionToken = IMPORT_REVIEW_PAGING_DIRECTION_TOKEN,
        statusToken = IMPORT_REVIEW_PAGING_STATUS_TOKEN,
        fundingState = ImportFundingState.SETTLED,
        fundingRuleId = IMPORT_REVIEW_PAGING_FUNDING_RULE_ID,
        fundingRuleVersion = IMPORT_REVIEW_PAGING_FUNDING_RULE_VERSION,
    )

/** The number of pages a full read of [candidates] at the product page size produces. */
internal fun importReviewPagingPageCount(candidates: Int): Int = (candidates + IMPORT_REVIEW_PAGING_PAGE_SIZE - 1) / IMPORT_REVIEW_PAGING_PAGE_SIZE

/**
 * The rendered kind half of one row's signature, built from this fixture's own facts.
 *
 * The presentation function that produces it (`importCandidateMetaText`) is `internal` to `app-ui`,
 * so an androidTest cannot call it; the frozen copy shape is therefore restated here exactly as
 * `AndroidScaleOracle.ScaleRow.metaText` already restates it for the scale rig. `ordinary_flow` is
 * the `candidate_kind` token the store writes for [ImportRecordKind.ORDINARY_FLOW_SOURCE]
 * (`SqlDelightImportSpineStore`'s kind gate), and the fixture's rows carry no duplicate group, so the
 * folded duplicate status renders `无`.
 */
private fun expectedKindLine(): String =
    "类型 ordinary_flow；发生 $IMPORT_REVIEW_PAGING_OCCURRED_AT；方向 $IMPORT_REVIEW_PAGING_DIRECTION_TOKEN；" +
        "状态 $IMPORT_REVIEW_PAGING_STATUS_TOKEN；重复 无"

/**
 * Validates one database file and returns the FIRST failure as a readable reason (null = valid).
 * Every count is read from the file, never assumed from what the builder intended to write.
 */
internal fun importReviewPagingValidation(
    databaseFile: File,
    candidates: Int = IMPORT_REVIEW_PAGING_CANDIDATES,
): String? {
    if (!databaseFile.isFile) return "${databaseFile.name} is not a readable file"
    return runCatching {
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            validateLibrary(database, candidates)
        }
    }.getOrElse { failure -> "${failure::class.simpleName}: ${failure.message}" }
}

@Suppress("LongMethod")
private fun validateLibrary(
    database: SQLiteDatabase,
    candidates: Int,
): String? {
    fun expect(
        valid: Boolean,
        reason: () -> String,
    ): String? = if (valid) null else reason()

    expect(database.rawQuery("PRAGMA quick_check", null).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "ok" }) {
        "PRAGMA quick_check did not return ok"
    }?.let { return it }
    expect(
        database.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> !cursor.moveToNext() },
    ) { "foreign_key_check reported rows" }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM import_candidate WHERE ledger_id=?", IMPORT_REVIEW_PAGING_LEDGER_ID) == candidates.toLong()) {
        "import_candidate count is not $candidates"
    }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM import_source_record WHERE ledger_id=?", IMPORT_REVIEW_PAGING_LEDGER_ID) == candidates.toLong()) {
        "import_source_record count is not $candidates"
    }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM import_request WHERE ledger_id=?", IMPORT_REVIEW_PAGING_LEDGER_ID) == candidates.toLong()) {
        "import_request count is not $candidates"
    }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM import_duplicate_candidate WHERE ledger_id=?", IMPORT_REVIEW_PAGING_LEDGER_ID) == 0L) {
        "the page library must hold no duplicate group, or the list would render a second class group"
    }?.let { return it }
    // Every candidate still needs the user's decision: the class group the test asserts on.
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_candidate_status_history WHERE ledger_id=? AND status=?",
            IMPORT_REVIEW_PAGING_LEDGER_ID,
            IMPORT_REVIEW_PAGING_PENDING_STATUS,
        ) == candidates.toLong(),
    ) { "the candidates do not all carry the $IMPORT_REVIEW_PAGING_PENDING_STATUS status" }?.let { return it }
    // The fixture's rows share one business tuple except for the amount; distinct amounts are what
    // keep the ordered pages distinguishable by their rendered signatures.
    expect(
        scalarLong(
            database,
            "SELECT COUNT(DISTINCT amount_minor) FROM import_source_record WHERE ledger_id=? AND input_ref=?",
            IMPORT_REVIEW_PAGING_LEDGER_ID,
            IMPORT_REVIEW_PAGING_INPUT_REF,
        ) == candidates.toLong(),
    ) { "the library's amounts are not distinct" }?.let { return it }
    expect(
        scalarLong(
            database,
            "SELECT COUNT(*) FROM import_source_record WHERE ledger_id=? AND input_ref=? AND record_kind=? AND " +
                "contract_version=? AND completeness=? AND currency_code=? AND currency_precision=? AND occurred_at=? AND " +
                "direction_token=? AND status_token=? AND funding_state=?",
            IMPORT_REVIEW_PAGING_LEDGER_ID,
            IMPORT_REVIEW_PAGING_INPUT_REF,
            ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue,
            ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion.toString(),
            IMPORT_REVIEW_PAGING_COMPLETENESS_TOKEN,
            IMPORT_REVIEW_PAGING_CURRENCY_CODE,
            IMPORT_REVIEW_PAGING_CURRENCY_PRECISION.toString(),
            IMPORT_REVIEW_PAGING_OCCURRED_AT,
            IMPORT_REVIEW_PAGING_DIRECTION_TOKEN,
            IMPORT_REVIEW_PAGING_STATUS_TOKEN,
            ImportFundingState.SETTLED.name,
        ) == candidates.toLong(),
    ) { "the library's source facts are not the frozen business tuple" }?.let { return it }
    // A page-crossing fixture must not move the ledger: no formal transaction, no confirmation.
    expect(scalarLong(database, "SELECT COUNT(*) FROM ledger_transaction WHERE ledger_id=?", IMPORT_REVIEW_PAGING_LEDGER_ID) == 0L) {
        "the fixture must not create a formal transaction"
    }?.let { return it }
    expect(scalarLong(database, "SELECT COUNT(*) FROM import_confirmation WHERE ledger_id=?", IMPORT_REVIEW_PAGING_LEDGER_ID) == 0L) {
        "the fixture must not create a confirmation"
    }?.let { return it }
    return null
}

private fun readFixture(
    main: File,
    candidates: Int,
): ImportReviewPagingFixture =
    SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        val candidateIds = ArrayList<String>(candidates)
        val amounts = ArrayList<Long>(candidates)
        database
            .rawQuery(
                "SELECT candidate.candidate_id, source.amount_minor FROM import_candidate AS candidate " +
                    "JOIN import_source_record AS source " +
                    "ON source.ledger_id = candidate.ledger_id AND source.source_id = candidate.source_id " +
                    "WHERE candidate.ledger_id=? ORDER BY candidate.candidate_id ASC",
                arrayOf(IMPORT_REVIEW_PAGING_LEDGER_ID),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    candidateIds += cursor.getString(0)
                    amounts += cursor.getLong(1)
                }
            }
        ImportReviewPagingFixture(
            generation = IMPORT_REVIEW_PAGING_GENERATION,
            candidateCount = candidateIds.size,
            candidateIds = candidateIds,
            amountsMinor = amounts,
            kindLine = expectedKindLine(),
        )
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
