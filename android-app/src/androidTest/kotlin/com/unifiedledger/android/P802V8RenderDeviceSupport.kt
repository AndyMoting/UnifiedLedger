package com.unifiedledger.android

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.SearchCondition
import com.unifiedledger.application.SearchPage
import com.unifiedledger.application.SearchReadResult
import com.unifiedledger.application.SearchTimeWindow
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.ui.ImportFilePickResultChannel
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * P8-02 V-8 render terminal (P8-07 Q5): the frozen facts of the 61,000-transaction sample and of the
 * reading this case records.
 *
 * WHAT THE TERMINAL IS. 「首屏渲染完成」 is measured IN PROCESS: after the submit action the case
 * samples `UiAutomation.takeScreenshot()` at a fixed interval, crops the result region, downscales it
 * and compares the pixel grid. The completion instant is the FIRST sample whose grid equals the
 * finally settled grid, minus the submit action's dispatch instant; the reading's time resolution is
 * the sampling interval PLUS the measured submit-to-first-sample overhead (`firstSampleAfterSubmitMs`
 * on the reading line). A port return, the accessibility tree and `gfxinfo`/framestats are all
 * explicitly NOT the terminal (stage-acceptance design section 1.4 and 3.3; `D-311` item 2; the
 * `D-166` framestats limits) — the tree is used only to VALIDATE that the measured region is the
 * result surface, never to time it. That validation happens at the ONE instant a settled pixel state
 * is accepted: the tree must then show the result header at the frozen count, must not show the list
 * face's static loading copy, and must place a result-list node inside the measured band, while the
 * app window keeps the bounds the band was cut from.
 *
 * NO THRESHOLD. The reading is an upper bound and is recorded, never judged for speed (P8-02 spec
 * section 5 V-8 with D2=A; stage-acceptance design section 3.3 「沿用原判据，不新增耗时阈值」).
 *
 * THE TWO PREFIXES ARE DISJOINT. Exactly ONE logcat line carries the reading prefix; every diagnostic
 * line of this rig carries the diagnostic prefix instead, so the rig's own gate can require the reading
 * line exactly once and a stray second line is a red rather than a choice.
 */
internal object P802V8RenderFacts {
    /** The one prefix of the reading line. No other line of this rig may carry it. */
    const val READING_PREFIX = "P802-V8R"

    /** The prefix of every diagnostic line this rig logs before and around the reading. */
    const val DIAGNOSTIC_PREFIX = "P802V8PREP"

    /** The frozen condition-group label, so this reading is comparable with the first-round one. */
    const val CONDITION_LABEL = "fixed-note+tag+merchant0"

    /**
     * The sampling interval of the pixel terminal. The reading's time resolution is this interval
     * PLUS the fixed submit-to-first-sample overhead the reading line carries as
     * `firstSampleAfterSubmitMs`, never this interval on its own.
     */
    const val SAMPLE_INTERVAL_MILLIS = 50

    /** Consecutive equal samples that define the FINAL settled state (the case freezes this). */
    const val SETTLE_SAMPLES = 3

    /** The vector's sample size: TRANSACTIONS, not import candidates (P8-02 spec section 5 V-8). */
    const val EXPECTED_TRANSACTIONS = 61000

    /** The frozen page size of the search read (P8-02 spec section 2.3). */
    const val EXPECTED_PAGE_ROWS = 50

    /** The fixed group's construction manifest: 61,000 / every-70th annotation / merchant-0. */
    const val EXPECTED_TOTAL_COUNT = 436

    /** The note arm's manifest on its own; a sample without it would not exercise the keyword arm. */
    const val EXPECTED_NOTE_MATCHES = 8715

    /** The case's own bound on the settle loop; a band that never settles fails loudly instead. */
    const val SETTLE_TIMEOUT_MILLIS = 20_000L

    /**
     * The minimum spacing between two accept-time semantic-tree probes, in nanoseconds.
     *
     * A tree observation costs a second-scale read, so it never runs inside the 50 ms sampling path:
     * the case reads the tree only when a candidate settled state appears, and at most this often.
     */
    const val TREE_PROBE_INTERVAL_NANOS = 500_000_000L

    /**
     * The hard bound on the sample count: at most this many comparison grids are ever held (about
     * 12 MB at the frozen grid size), and no full frame outlives its own sample.
     */
    const val MAX_SAMPLES = 240

    /** The comparison grid: the band downscaled before `getPixels`, so no frame is kept whole. */
    const val GRID_WIDTH = 192

    /** The comparison grid's height. */
    const val GRID_HEIGHT = 64

    /** The share of the app window, from its bottom, that holds the result list. */
    const val RESULT_BAND_PERCENT = 40

    /** Logcat truncates a long message; the reading is chunked, and only chunk 0 carries the prefix. */
    const val LOGCAT_CHUNK = 1000

    /** The home surface's search capsule (the product's own copy, mirrored by the flow driver). */
    val homeSearchCapsule: String = P807Q2Flow1Facts.HOME_SEARCH_CAPSULE

    /** The keyword marker of the note arm (the frozen sample recipe's own constant). */
    val noteMarker: String = P802SearchDeviceFacts.NOTE_MARKER

    /** The tag the fixed group selects, by id and by the name the condition area renders. */
    val tagId: String = P802SearchDeviceFacts.TAG_ID
    val tagName: String = P802SearchDeviceFacts.TAG_NAME

    /** The merchant the fixed group selects, as the vector names it. */
    val merchantId: String = P802SearchDeviceFacts.MERCHANT_ZERO_ID
    val merchantName: String = P802SearchDeviceFacts.MERCHANT_NAMES.first()

    /** The rendered result header, so the case can prove the reading region is the result list. */
    const val RESULTS_HEADER = "搜索结果"

    /**
     * The list face's own static loading copy. Nothing about this row changes while the read runs, so
     * a band holding only this row settles on its own; a settled state is never accepted while it is
     * on screen.
     */
    const val LOADING_TEXT = "正在搜索…"
}

/**
 * One prepared sample: everything the reading line and the case's structural assertions need.
 *
 * [pageRows] and [totalCount] are the PORT's page facts, asserted from the same 61k file the app then
 * opens; they are the structural bound (`pageRows == 50`, `totalCount == 436`), NOT the terminal.
 */
internal data class P802V8SeededSample(
    val ledgerId: LedgerId,
    val transactionCount: Long,
    val schemaVersion: Long,
    val windowStartInclusive: Instant,
    val windowEndExclusive: Instant,
    val pageRows: Int,
    val totalCount: Long,
    val hasMore: Boolean,
    val noteArmMatches: Long,
)

/**
 * The real-storage seed of the V-8 sample.
 *
 * THE ROUTE THIS FILE CHOOSES (and why it is the only one that leaves V-8's own claim standing):
 * 1. the app's REAL host directory is resolved by the product's own entry (`scaleFixtureLayout`), the
 *    `/proc/self/fd` guard proves no earlier case in this process opened the ledger, and the app's
 *    stable-storage state is cleared — the P8-07 flow-1 seed's shapes, reused unchanged
 *    (`P807Q2Flow1DeviceSupport.kt:2013-2048`, `:478-498`);
 * 2. the product creates the ledger and its generation file through
 *    `openAndroidStableStorageLedger`, then CLOSES the graph;
 * 3. the same file the product just created is opened with a native connection and the 61k rows are
 *    written in ONE transaction with chunked multi-row INSERTs — the only writer fast enough
 *    (`P802SearchDeviceFixture.kt:180-243`, which measured the per-row product path at ~30 minutes);
 * 4. the product REOPENS the graph and the reopen is asserted to land on the file this seed wrote
 *    (`P802SearchDeviceFixture.kt:185-187`), so the app's own reads can only come from this sample;
 * 5. the port is read on that reopened graph for the structural bound, the graph is closed, and the
 *    launched app opens the very same generation file.
 *
 * WHY THE REAL DIRECTORY IS REACHABLE. The frozen fixture proves the whole sequence on a REDIRECTED
 * context whose wrapper overrides only `getDatabasePath` (`P801ChainFixture.kt:519-526`): the product
 * entry resolves its layout from that one call, so the redirected case and the real one differ in the
 * directory alone. Writing generation files under the real host directory is also an established
 * pattern of this suite (`ImportScaleDuplicateFixtureSupport.kt:356-359` resolves that directory, and
 * `:194-217` writes a generation file and the active pointer under it), and the fixture's native writer
 * works on exactly the file the product created (`P802SearchDeviceFixture.kt:223-244`).
 */
internal object P802V8RenderSeed {
    /** The frozen four-item condition group of V-8 (keyword + default window + tag + merchant). */
    val fixedCondition: SearchCondition =
        SearchCondition(
            keyword = P802V8RenderFacts.noteMarker,
            window = SearchTimeWindow.LastNinetyDays,
            tagIds = setOf(TagId(P802V8RenderFacts.tagId)),
            merchantFilterId = MerchantId(P802V8RenderFacts.merchantId),
        )

    /**
     * Seeds the sample into the app's own stable storage and returns its facts.
     *
     * Call only in a process that has not opened the ledger yet: the guard refuses otherwise, because
     * a product runtime from an earlier case would keep writing to the replaced file.
     */
    fun prepare(
        context: Context,
        log: (String) -> Unit,
    ): P802V8SeededSample {
        val layout = scaleFixtureLayout(context)
        p807Q2Flow1RequireNoOpenLedgerHandle(layout.hostDirectory, log)
        p807Q2Flow1ResetLedgerStorage(context, layout, log)
        val driverNames = mutableListOf<String>()
        // ONE base instant for the whole sample: every generated instant is relative to it, so the two
        // window bounds the reading reports are the sample's real bounds and not a second clock read.
        val base = baseInstant()
        val first = openProductGraph(context, driverNames)
        check(driverNames.size == 1) { "the seed's first product open must resolve exactly one file, got $driverNames" }
        val ledgerId =
            try {
                first.facade.ledgerId
            } finally {
                // The native writer must own the file alone, and the reopen below proves it is the SAME one.
                first.close()
            }
        val written = writeFixtureRows(File(driverNames.single()), base)
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} seed file=${File(driverNames.single()).name} " +
                "transactions=${written.transactionCount} missingProjection=${written.missingProjectionRows} " +
                "schema=${written.schemaVersion}",
        )
        val reopened = openProductGraph(context, driverNames)
        try {
            check(driverNames.size == 2 && driverNames[0] == driverNames[1]) {
                "the reopen must land on the file this seed wrote: $driverNames"
            }
            check(written.transactionCount == P802V8RenderFacts.EXPECTED_TRANSACTIONS.toLong()) {
                "the sample must hold ${P802V8RenderFacts.EXPECTED_TRANSACTIONS} transactions, found ${written.transactionCount}"
            }
            check(written.missingProjectionRows == 0L) { "every version row must carry its statistics projection" }
            val port = checkNotNull(reopened.querySearchPage) { "the production graph must wire the search read" }
            val page = pageOf(port.query(ledgerId, fixedCondition))
            check(page.entries.size == P802V8RenderFacts.EXPECTED_PAGE_ROWS) {
                "the first page must hold ${P802V8RenderFacts.EXPECTED_PAGE_ROWS} rows, got ${page.entries.size}"
            }
            check(page.totalCount == P802V8RenderFacts.EXPECTED_TOTAL_COUNT.toLong()) {
                "the fixed group must total ${P802V8RenderFacts.EXPECTED_TOTAL_COUNT}, got ${page.totalCount}"
            }
            val noteArm = pageOf(port.query(ledgerId, SearchCondition(keyword = P802V8RenderFacts.noteMarker)))
            check(noteArm.totalCount == P802V8RenderFacts.EXPECTED_NOTE_MATCHES.toLong()) {
                "the note arm must total ${P802V8RenderFacts.EXPECTED_NOTE_MATCHES}, got ${noteArm.totalCount}"
            }
            return P802V8SeededSample(
                ledgerId = ledgerId,
                transactionCount = written.transactionCount,
                schemaVersion = written.schemaVersion,
                windowStartInclusive = base - (P802SearchDeviceFacts.SPREAD_DAYS - 1).days,
                windowEndExclusive = base + 1.seconds,
                pageRows = page.entries.size,
                totalCount = page.totalCount,
                hasMore = page.hasMore,
                noteArmMatches = noteArm.totalCount,
            )
        } finally {
            // The launched app must find the file CLOSED, so its own start opens this generation.
            reopened.close()
        }
    }

    /** The device clock truncated to the whole second; every generated instant is relative to it. */
    private fun baseInstant(): Instant = Instant.fromEpochSeconds(System.currentTimeMillis() / 1000L)

    private fun pageOf(result: SearchReadResult): SearchPage {
        check(result is SearchReadResult.Success) { "the search read must answer a page, got $result" }
        return (result as SearchReadResult.Success).page
    }

    private fun openProductGraph(
        context: Context,
        names: MutableList<String>,
    ): CloseableLedgerGraph =
        openAndroidStableStorageLedger(
            context = context,
            importFilePickPort =
                AndroidImportFilePickPort<android.net.Uri>(
                    launchOpenDocument = {},
                    resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
                    openInputStream = { null },
                    onResult = {},
                ),
            importPickChannel = ImportFilePickResultChannel(),
            openDriver = { name -> createAndroidLedgerDatabase(context, name).also { names += name } },
        )

    /** One native write batch's read-back facts: what the file holds after the insertion. */
    private data class P802V8WrittenFacts(
        val transactionCount: Long,
        val missingProjectionRows: Long,
        val schemaVersion: Long,
    )

    private fun writeFixtureRows(
        mainFile: File,
        base: Instant,
    ): P802V8WrittenFacts {
        check(mainFile.isFile) { "the product open must have created the generation file: ${mainFile.name}" }
        SQLiteDatabase.openDatabase(mainFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            val preexisting = longQuery(database, "SELECT count(*) FROM ledger_transaction")
            check(preexisting == 0L) { "the product's fresh ledger must start with no transactions, found $preexisting" }
            database.beginTransaction()
            try {
                writeCatalogItems(database)
                writeTransactions(database, base)
                writeAnnotations(database, base)
                writeSemanticRows(database, base)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            return P802V8WrittenFacts(
                transactionCount = longQuery(database, "SELECT count(*) FROM ledger_transaction"),
                missingProjectionRows =
                    longQuery(database, "SELECT count(*) FROM transaction_version WHERE statistics_at_epoch_nanos IS NULL"),
                schemaVersion = longQuery(database, "PRAGMA user_version"),
            )
        }
    }

    private fun writeTransactions(
        database: SQLiteDatabase,
        base: Instant,
    ) {
        val ledger = P802SearchDeviceFacts.ledgerId.value
        val facts = P802SearchDeviceFacts
        insertChunked(
            database,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ",
            facts.TRANSACTION_COUNT,
        ) { index ->
            // rg11's ownership guard admits an `expense` semantic row only on a PREPAID_RECOGNITION
            // transaction; the frozen fixture folds the same UPDATE into its insert.
            val canonical =
                if (index == facts.SEMANTIC_INDEXES.getValue("rg11_posting_semantic")) "'PREPAID_RECOGNITION'" else "NULL"
            "('${transactionId(index)}', '$ledger', 'EXPENSE', $canonical)"
        }
        insertChunked(
            database,
            "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ",
            facts.TRANSACTION_COUNT,
        ) { index -> "('${postingSetId(index)}', '$ledger')" }
        insertChunked(
            database,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, " +
                "occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) VALUES ",
            facts.TRANSACTION_COUNT,
        ) { index ->
            val instant = statisticsInstant(base, index)
            val text = instant.toString()
            "('${versionId(index)}', '${transactionId(index)}', '$ledger', 1, '${postingSetId(index)}', " +
                "'$text', '$text', '$text', ${sqlLiteral(noteOf(index))}, ${projectionNanos(instant)})"
        }
        insertChunked(
            database,
            "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, " +
                "currency_code, currency_precision) VALUES ",
            facts.TRANSACTION_COUNT * POSTINGS_PER_CHAIN,
        ) { ordinal ->
            val index = ordinal / POSTINGS_PER_CHAIN
            val leg = ordinal % POSTINGS_PER_CHAIN
            val amount = 1_000L + index % 97 * 137L
            val (account, signedAmount) =
                if (leg == 0) facts.EXPENSE_ACCOUNT_ID to amount else facts.ASSET_ACCOUNT_ID to -amount
            "('${postingId(index, leg)}', '${postingSetId(index)}', '$ledger', $leg, '$account', $signedAmount, " +
                "'${CATALOG_MANAGED_CURRENCY.code}', ${CATALOG_MANAGED_CURRENCY.precision})"
        }
        insertChunked(
            database,
            "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ",
            facts.TRANSACTION_COUNT,
        ) { index -> "('${transactionId(index)}', '$ledger', '${versionId(index)}')" }
    }

    private fun writeAnnotations(
        database: SQLiteDatabase,
        base: Instant,
    ) {
        val ledger = P802SearchDeviceFacts.ledgerId.value
        val annotated = annotatedIndexes()
        insertChunked(
            database,
            "INSERT INTO transaction_annotation_revision(ledger_id, transaction_id, annotation_revision, " +
                "observed_transaction_version_id, merchant_id, request_id, created_at) VALUES ",
            annotated.size,
        ) { ordinal ->
            val index = annotated[ordinal]
            val merchant = "'merchant-${index / P802SearchDeviceFacts.ANNOTATION_STRIDE % P802SearchDeviceFacts.MERCHANT_NAMES.size}'"
            "('$ledger', '${transactionId(index)}', 1, '${versionId(index)}', $merchant, " +
                "'request-${transactionId(index)}', '${statisticsInstant(base, index)}')"
        }
        insertChunked(
            database,
            "INSERT INTO transaction_annotation_tag(ledger_id, transaction_id, annotation_revision, tag_id) VALUES ",
            annotated.size,
        ) { ordinal -> "('$ledger', '${transactionId(annotated[ordinal])}', 1, '${P802SearchDeviceFacts.TAG_ID}')" }
        insertChunked(
            database,
            "INSERT INTO transaction_annotation_current(ledger_id, transaction_id, annotation_revision) VALUES ",
            annotated.size,
        ) { ordinal -> "('$ledger', '${transactionId(annotated[ordinal])}', 1)" }
    }

    /**
     * The tag/merchant directory the keyword arms resolve against: the product's default seed carries
     * no items, so the fixture writes the same rows the product's own create command writes.
     */
    private fun writeCatalogItems(database: SQLiteDatabase) {
        val ledger = P802SearchDeviceFacts.ledgerId.value
        database.execSQL(
            "INSERT INTO catalog_tag(ledger_id, tag_id, active, tombstoned, revision) " +
                "VALUES ('$ledger', '${P802SearchDeviceFacts.TAG_ID}', 1, 0, 1)",
        )
        database.execSQL(
            "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                "VALUES ('$ledger', 'tag', '${P802SearchDeviceFacts.TAG_ID}', 1, '${P802SearchDeviceFacts.TAG_NAME}', 'CURRENT')",
        )
        P802SearchDeviceFacts.MERCHANT_NAMES.forEachIndexed { position, name ->
            database.execSQL(
                "INSERT INTO catalog_merchant(ledger_id, merchant_id, active, tombstoned, revision) " +
                    "VALUES ('$ledger', 'merchant-$position', 1, 0, 1)",
            )
            database.execSQL(
                "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                    "VALUES ('$ledger', 'merchant', 'merchant-$position', 1, '$name', 'CURRENT')",
            )
        }
        database.execSQL("INSERT OR IGNORE INTO catalog_item_version(ledger_id, version) VALUES ('$ledger', 0)")
        database.execSQL("UPDATE catalog_item_version SET version = version + 1 WHERE ledger_id = '$ledger'")
    }

    /** One posting-semantic row per table, on leg 0 of its transaction, against the leaf category. */
    private fun writeSemanticRows(
        database: SQLiteDatabase,
        base: Instant,
    ) {
        val ledger = P802SearchDeviceFacts.ledgerId.value
        val category = DEFAULT_EXPENSE_LEAF_ID
        P802SearchDeviceFacts.SEMANTIC_INDEXES.forEach { (table, index) ->
            val posting = postingId(index, 0)
            when (table) {
                "rg03_transfer_posting_semantic" ->
                    database.execSQL(
                        "INSERT INTO rg03_transfer_posting_semantic(ledger_id, posting_id, role, category_id, " +
                            "reconciliation_eligible) VALUES ('$ledger', '$posting', 'TRANSFER_FEE', '$category', 0)",
                    )
                "rg05_posting_semantic" ->
                    database.execSQL(
                        "INSERT INTO rg05_posting_semantic(ledger_id, posting_id, role, item_id, category_id, " +
                            "reconciliation_eligible) VALUES ('$ledger', '$posting', 'expense', 'item-v8', '$category', 0)",
                    )
                "rg06_posting_semantic" -> {
                    database.execSQL(
                        "INSERT INTO rg06_relation(ledger_id, relation_id, relation_type, payload_marker) " +
                            "VALUES ('$ledger', 'relation-v8', 'staged_payment', '{}')",
                    )
                    database.execSQL(
                        "INSERT INTO rg06_installment(ledger_id, relation_id, installment_index, payment_id, payment_role, " +
                            "amount_minor, currency_code, currency_precision, funding_account_id, transaction_id, " +
                            "transaction_version_id, posting_set_id, expense_posting_id, asset_posting_id, " +
                            "actual_payment_at, statistics_at) VALUES ('$ledger', 'relation-v8', 0, 'payment-v8', " +
                            "'DEPOSIT', 1000, '${CATALOG_MANAGED_CURRENCY.code}', ${CATALOG_MANAGED_CURRENCY.precision}, " +
                            "'${P802SearchDeviceFacts.ASSET_ACCOUNT_ID}', '${transactionId(index)}', '${versionId(index)}', " +
                            "'${postingSetId(index)}', '$posting', '${postingId(index, 1)}', " +
                            "'${statisticsInstant(base, index)}', '${statisticsInstant(base, index)}')",
                    )
                    database.execSQL(
                        "INSERT INTO rg06_posting_semantic(ledger_id, posting_id, payment_id, role, category_id, " +
                            "reconciliation_eligible) VALUES ('$ledger', '$posting', 'payment-v8', 'expense', '$category', 0)",
                    )
                }
                "rg11_posting_semantic" ->
                    database.execSQL(
                        "INSERT INTO rg11_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible, category_id) " +
                            "VALUES ('$ledger', '$posting', 'expense', 0, '$category')",
                    )
                else ->
                    database.execSQL(
                        "INSERT INTO $table(ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                            "VALUES ('$ledger', '$posting', 'expense', '$category', 0)",
                    )
            }
        }
    }

    /** Every transaction carrying an annotation (the merchant arm's denominator), ascending. */
    private fun annotatedIndexes(): List<Int> =
        (0 until P802SearchDeviceFacts.TRANSACTION_COUNT).filter { it % P802SearchDeviceFacts.ANNOTATION_STRIDE == 0 }

    /** The `index`-th statistics instant: `base - (index % SPREAD_DAYS) days`, whole seconds. */
    private fun statisticsInstant(
        base: Instant,
        index: Int,
    ): Instant = base - (index % P802SearchDeviceFacts.SPREAD_DAYS).days

    private fun transactionId(index: Int): String = "tx-${(index + 1).toString().padStart(5, '0')}"

    private fun postingSetId(index: Int): String = "${transactionId(index)}-posting-set-1"

    private fun versionId(index: Int): String = "${transactionId(index)}-version-1"

    private fun postingId(
        index: Int,
        leg: Int,
    ): String = "${transactionId(index)}-posting-$leg"

    /** The note arm's marker placement: every 7th transaction, NULL everywhere else. */
    private fun noteOf(index: Int): String? =
        if (index % P802SearchDeviceFacts.NOTE_STRIDE == 0) P802SearchDeviceFacts.NOTE_MARKER else null

    /** Whole-second instants project exactly, so the numeric column and the text agree. */
    private fun projectionNanos(instant: Instant): Long = instant.toEpochMilliseconds() * 1_000_000L

    private fun insertChunked(
        database: SQLiteDatabase,
        statementPrefix: String,
        count: Int,
        row: (Int) -> String,
    ) {
        var start = 0
        while (start < count) {
            val end = minOf(start + P802SearchDeviceFacts.CHUNK, count)
            database.execSQL(statementPrefix + (start until end).joinToString(", ") { row(it) })
            start = end
        }
    }

    private fun longQuery(
        database: SQLiteDatabase,
        sql: String,
    ): Long =
        database.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst()) { "the seed probe returned no row: $sql" }
            cursor.getLong(0)
        }

    /** A SQL string literal, or `NULL` for an absent value. */
    private fun sqlLiteral(value: String?): String = if (value == null) "NULL" else "'${value.replace("'", "''")}'"

    private const val POSTINGS_PER_CHAIN = 2
}

/** The full lowercase sha256 of this build's app APK, read from the installed artifact. */
internal fun p802V8ApkSha256(context: Context): String {
    val source = File(context.applicationInfo.sourceDir)
    check(source.isFile) { "the app APK must be readable at ${source.name}" }
    val digest = MessageDigest.getInstance("SHA-256")
    source.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte) }
}

/**
 * The band of a window the result list occupies once the condition area is out of the way.
 *
 * The cut is purely geometric — the window's lower [P802V8RenderFacts.RESULT_BAND_PERCENT] percent —
 * and its lower edge is the WINDOW's bottom, never the display's, so an inset or a second window can
 * never extend the crop past the surface being measured. What makes the band a result region is the
 * accept-time node check in [P802V8ResultSurface.confirmResultView], not this arithmetic.
 */
internal fun p802V8ResultBand(window: Rect): Rect {
    val height = window.height()
    check(height > 0) { "the app window must have a positive height, got $window" }
    val top = window.top + height * (100 - P802V8RenderFacts.RESULT_BAND_PERCENT) / 100
    return Rect(window.left, top, window.right, window.bottom)
}

/** Two samples are the same state only when their whole comparison grid is identical. */
internal fun p802V8SameState(
    a: IntArray,
    b: IntArray,
): Boolean = a.contentEquals(b)

/**
 * The index of the LAST sample of the first run of [settleSamples] mutually equal samples, scanning
 * from the newest sample backwards; `null` while no such run exists.
 */
internal fun p802V8FirstSettledIndex(
    samples: List<IntArray>,
    settleSamples: Int,
): Int? {
    if (settleSamples <= 1 || samples.size < settleSamples) return null
    var index = samples.size - 1
    while (index >= settleSamples - 1) {
        var offset = 1
        var equal = true
        while (offset < settleSamples) {
            if (!p802V8SameState(samples[index], samples[index - offset])) {
                equal = false
                break
            }
            offset++
        }
        if (equal) return index
        index--
    }
    return null
}

/**
 * The in-process pixel sampler of the result band: ONE frame at a time, cropped, downscaled and copied
 * into a comparison grid, with both intermediate bitmaps recycled immediately (the memory discipline
 * of `AndroidScaleColdstartForensicsCollector.kt:218-239` and `AndroidScaleStageForensics.kt:264-288`).
 *
 * No full frame is ever kept: the two bitmaps die inside [sample], and the caller holds only the grid.
 */
internal class P802V8PixelSampler(
    private val automation: UiAutomation,
    private val band: Rect,
) {
    /** One sample of the band's grid, or `null` when the platform refused a screenshot. */
    fun sample(): IntArray? {
        val full = automation.takeScreenshot() ?: return null
        // The band comes from the app window's bounds; the frame comes from the display. Both are
        // clamped into the frame, so a status-bar inset can never make the crop invalid.
        val left = band.left.coerceIn(0, full.width - 1)
        val top = band.top.coerceIn(0, full.height - 1)
        val width = band.width().coerceAtMost(full.width - left).coerceAtLeast(1)
        val height = band.height().coerceAtMost(full.height - top).coerceAtLeast(1)
        // The band is at most (100 - RESULT_BAND_PERCENT)% of the window's height, so the crop is
        // smaller than the frame and createBitmap always returns a copy the frame's recycle cannot take.
        val cropped =
            try {
                Bitmap.createBitmap(full, left, top, width, height)
            } finally {
                full.recycle()
            }
        val scaled = Bitmap.createScaledBitmap(cropped, P802V8RenderFacts.GRID_WIDTH, P802V8RenderFacts.GRID_HEIGHT, true)
        val pixels = IntArray(P802V8RenderFacts.GRID_WIDTH * P802V8RenderFacts.GRID_HEIGHT)
        try {
            scaled.getPixels(pixels, 0, P802V8RenderFacts.GRID_WIDTH, 0, 0, P802V8RenderFacts.GRID_WIDTH, P802V8RenderFacts.GRID_HEIGHT)
        } finally {
            if (scaled !== cropped) cropped.recycle()
            scaled.recycle()
        }
        return pixels
    }
}

/**
 * What the semantic tree proved at the instant a settled pixel state was accepted: the rendered
 * result header's total, and the bounds of one result-list node that lies inside the measured band.
 *
 * [rowBounds] is the node the band was checked against, so "the measured region is the result list"
 * is a comparison of two rects, not an assumption about a window percentage.
 */
internal data class P802V8ResultView(
    val renderedTotal: Long,
    val rowBounds: Rect,
)

/**
 * The searched surface's driver: the real app through the flow-1 driver's own launch/click/scroll
 * discipline, plus the two steps this case must own — placing the frozen condition group and stamping
 * the submit action's instant WITHOUT waiting for the result surface first.
 *
 * The last point is the whole measurement: the flow driver's `submitSearch` waits for the result
 * header before returning, so using it would consume the very interval the reading is about. This
 * class taps the submit node itself and hands the dispatch instant back to the caller.
 */
internal class P802V8ResultSurface(
    instrumentation: Instrumentation,
    private val log: (String) -> Unit,
) {
    private val flow = P807Q2Flow1Ui(instrumentation, log)

    /** Launches the app on the seeded storage and opens the search surface from the home capsule. */
    fun launchAndOpenSearch() {
        flow.launch()
        check(flow.awaitHomeCapsule()) { "the launched home must render the live overview's search capsule" }
        flow.clickApp(P802V8RenderFacts.homeSearchCapsule)
        flow.awaitAppText(P807Q2Flow1Facts.SEARCH_WINDOW_HEADER)
    }

    /** The app window's bounds, the frame every comparison grid is cropped from. */
    fun windowBounds(): Rect {
        val root = checkNotNull(flow.ui.root()) { "the app must expose a root window" }
        return flow.bounds(root)
    }

    /**
     * Places V-8's frozen condition group: the note keyword, the DEFAULT 90-day window (untouched,
     * which is what the frozen group means), the tag and the merchant.
     *
     * Each placement is verified from the node's own state after the click, so a click the product
     * never received fails here instead of leaving a silently different condition behind.
     */
    fun applyFixedCondition() {
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} enterSearch surface=[${flow.surfaceTitle()}] " +
                "editableFields=${flow.editableFieldCount()}",
        )
        check(flow.editableFieldCount() == 1) {
            "the search surface must open with the keyword field as its only editable field (the frozen default " +
                "window), saw ${flow.editableFieldCount()}"
        }
        check(flow.setFieldText(1, P802V8RenderFacts.noteMarker)) {
            "the note keyword must land in the field"
        }
        // The IME is dismissed BEFORE the two control clicks: the semantic click path does not need it,
        // but its coordinate fallback would land on the keyboard, and the pre-submit frame must be free
        // of the IME so the comparison band is the app's own surface.
        check(flow.hideKeyboardIfShown()) { "the input method must be gone after the keyword was typed" }
        check(toggleTag()) { "the tag ${P802V8RenderFacts.tagName} must be checked after the click" }
        check(selectMerchant()) { "the merchant ${P802V8RenderFacts.merchantName} must be selected after the click" }
        check(flow.hideKeyboardIfShown()) { "the input method must be gone before the reading" }
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} condition keyword=${P802V8RenderFacts.noteMarker} " +
                "tag=${P802V8RenderFacts.tagName} merchant=${P802V8RenderFacts.merchantName} window=default-90d",
        )
    }

    /** Makes the submit affordance reachable (the condition area may have been scrolled), bounded. */
    fun prepareSubmit() {
        flow.scrollBackTo(P807Q2Flow1Facts.SEARCH_SUBMIT_DESCRIPTION)
    }

    /**
     * Taps the submit affordance and returns the instant its click was DISPATCHED.
     *
     * The instant is taken before the accessibility action, so the gesture's own dispatch cost counts
     * INTO the reading — the number is an upper bound and can never be flattered.
     */
    fun submitDispatchNanos(): Long {
        val node =
            checkNotNull(flow.findAppNode { candidate -> flow.descriptionOf(candidate) == P807Q2Flow1Facts.SEARCH_SUBMIT_DESCRIPTION }) {
                "the submit affordance (${P807Q2Flow1Facts.SEARCH_SUBMIT_DESCRIPTION}) must be on this surface"
            }
        check(node.isEnabled && node.isVisibleToUser) { "the submit affordance must be enabled and visible before the reading" }
        val dispatched = System.nanoTime()
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} submit dispatchNanos=$dispatched")
        flow.tap(node)
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} submit returnedNanos=${System.nanoTime()}")
        return dispatched
    }

    /**
     * The total the surface RENDERS in its result header (`搜索结果 · 共 N 笔`), or `null` when the list
     * face has not rendered yet. This RE-READS the header after the settle so the reading line can be
     * bound to the count it was measured with; the accept-time region proof is [confirmResultView].
     */
    fun renderedTotal(deadlineMillis: Long = P802V8RenderFacts.SETTLE_TIMEOUT_MILLIS): Long? {
        val deadline = SystemClock.elapsedRealtime() + deadlineMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            flow.appNodes()
                .firstOrNull { node -> flow.textOf(node).startsWith(P802V8RenderFacts.RESULTS_HEADER) }
                ?.let { node ->
                    val text = flow.textOf(node)
                    log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} resultHeader=[$text] bounds=${flow.bounds(node)}")
                    return RENDERED_TOTAL.find(text)?.groupValues?.get(1)?.toLongOrNull()
                }
            SystemClock.sleep(P802V8RenderFacts.SAMPLE_INTERVAL_MILLIS.toLong())
        }
        return null
    }

    /**
     * The accept-time tree gate: a settled pixel state is accepted only when the surface is, at that
     * very instant, the rendered result list.
     *
     * ONE tree read answers three questions, and all three are needed:
     * 1. the app window still has the bounds the band was cut from, so the band measures the same
     *    window it was derived from (a window that re-laid out mid-reading would silently re-aim it);
     * 2. the result header `搜索结果 · 共 436 笔` has rendered AND the list face's static `正在搜索…` row
     *    is gone — the loading row does not animate, so a settled grid made of it alone would end the
     *    reading early and quietly report a number that is too small;
     * 3. at least one result-list node below that header lies inside the measured band, which turns
     *    "the measured region is the result list" into an assertion about two rects.
     *
     * A surface that has not got there YET returns `null` and the caller keeps sampling. A band that
     * holds no result-list node is a red instead: no later sample can make that true.
     */
    fun confirmResultView(
        expectedWindow: Rect,
        band: Rect,
    ): P802V8ResultView? {
        val window = windowBounds()
        check(window == expectedWindow) {
            "the app window moved during the reading: the band $band was cut from $expectedWindow, " +
                "the window now reads $window"
        }
        val nodes = flow.appNodes()
        val header = nodes.firstOrNull { node -> flow.textOf(node).startsWith(P802V8RenderFacts.RESULTS_HEADER) }
        val loading = nodes.firstOrNull { node -> flow.textOf(node).contains(P802V8RenderFacts.LOADING_TEXT) }
        if (header == null || loading != null) {
            log(
                "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} settled candidate pending: " +
                    "header=${header?.let { node -> flow.textOf(node) }} " +
                    "loading=[${loading?.let { node -> flow.textOf(node) }}]",
            )
            return null
        }
        val text = flow.textOf(header)
        val total = RENDERED_TOTAL.find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (total == null) {
            log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} settled candidate pending: header [$text] carries no total")
            return null
        }
        val headerBounds = flow.bounds(header)
        val belowHeader =
            nodes
                .map { node -> flow.bounds(node) }
                .filter { rect -> rect.top >= headerBounds.bottom && rect.width() > 0 && rect.height() > 0 }
        val row =
            checkNotNull(belowHeader.firstOrNull { rect -> Rect.intersects(rect, band) }) {
                "the measured band $band holds no result-list node below the header [$text]: " +
                    "headerBounds=$headerBounds nearest=${belowHeader.minByOrNull { rect -> abs(rect.top - band.top) }}"
            }
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} settled view header=[$text] headerBounds=$headerBounds " +
                "rowBounds=$row band=$band",
        )
        return P802V8ResultView(renderedTotal = total, rowBounds = row)
    }

    /** Checks the checkbox whose row carries [label] and reports whether the row's box is checked. */
    private fun toggleTag(): Boolean {
        flow.seekApp(P802V8RenderFacts.tagName)
        val target = checkableNear(P802V8RenderFacts.tagName) ?: return false
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} tag checkbox ${describe(target)}")
        flow.tap(target)
        flow.settle()
        return checkableNear(P802V8RenderFacts.tagName)?.isChecked == true
    }

    /** Selects the merchant radio whose row carries [label] and reports whether it is selected. */
    private fun selectMerchant(): Boolean {
        flow.seekApp(P802V8RenderFacts.merchantName)
        val target = checkableNear(P802V8RenderFacts.merchantName) ?: return false
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} merchant radio ${describe(target)}")
        flow.tap(target)
        flow.settle()
        return checkableNear(P802V8RenderFacts.merchantName)?.isChecked == true
    }

    /**
     * The checkable node of the row that carries [label]: the checkbox/radio is a SEPARATE semantics node
     * from its label text, so it is addressed structurally — same row by vertical overlap, nearest
     * vertical centre — instead of by the label's text (a text node is not clickable).
     */
    private fun checkableNear(label: String): AccessibilityNodeInfo? {
        val labelNode = flow.findAppNode { node -> flow.textOf(node) == label } ?: return null
        val labelBounds = flow.bounds(labelNode)
        return flow.appNodes()
            .filter { node -> node.isCheckable }
            .filter { node ->
                val bounds = flow.bounds(node)
                bounds.top < labelBounds.bottom && bounds.bottom > labelBounds.top
            }
            .minByOrNull { node -> abs(flow.bounds(node).centerY() - labelBounds.centerY()) }
    }

    /** The node's own shape, so a refused click is diagnosable from the log alone. */
    private fun describe(node: AccessibilityNodeInfo): String =
        "class=${node.className} enabled=${node.isEnabled} visible=${node.isVisibleToUser} " +
            "checkable=${node.isCheckable} checked=${node.isChecked} bounds=${flow.bounds(node)}"

    private companion object {
        /** The rendered result header's own copy: `搜索结果 · 共 N 笔`. */
        val RENDERED_TOTAL = Regex(P802V8RenderFacts.RESULTS_HEADER + " · 共 ([0-9]+) 笔")
    }
}

/**
 * Everything one V-8 reading line carries; [renderFirstPageMs] is the measured upper bound.
 *
 * [firstSampleAfterSubmitMs] is the fixed overhead between the submit dispatch and the first sample:
 * the resolution of [renderFirstPageMs] is [sampleIntervalMillis] plus that overhead, so the reading
 * can never be read as if its only quantisation were the interval.
 */
internal data class P802V8Reading(
    val fixtureTransactions: Long,
    val windowStartInclusive: Instant,
    val windowEndExclusive: Instant,
    val sampleIntervalMillis: Int,
    val firstSampleAfterSubmitMs: Double,
    val samples: Int,
    val settleSamples: Int,
    val renderFirstPageMs: Double,
    val pageRows: Int,
    val totalCount: Long,
    val schemaVersion: Long,
    val apkSha256: String,
)

/**
 * Emits the reading line and returns it, so the caller can log it in one place and the gate can find
 * exactly one such line on disk.
 *
 * The line is chunked only because logcat truncates a long message; every chunk but the first starts
 * mid-line, so the prefix stays the single anchor.
 */
internal fun p802V8EmitReadingLine(
    log: (String) -> Unit,
    reading: P802V8Reading,
): String {
    val line =
        "${P802V8RenderFacts.READING_PREFIX} fixture=${reading.fixtureTransactions} " +
            "condition=${P802V8RenderFacts.CONDITION_LABEL} " +
            "windowStart=${reading.windowStartInclusive} windowEnd=${reading.windowEndExclusive} " +
            "sampleIntervalMs=${reading.sampleIntervalMillis} samples=${reading.samples} " +
            "firstSampleAfterSubmitMs=${String.format(Locale.ROOT, "%.1f", reading.firstSampleAfterSubmitMs)} " +
            "settleSamples=${reading.settleSamples} " +
            "renderFirstPageMs=${String.format(Locale.ROOT, "%.1f", reading.renderFirstPageMs)} " +
            "pageRows=${reading.pageRows} totalCount=${reading.totalCount} " +
            "schema=${reading.schemaVersion} apk=${reading.apkSha256}"
    for (chunk in line.chunked(P802V8RenderFacts.LOGCAT_CHUNK)) log(chunk)
    return line
}
