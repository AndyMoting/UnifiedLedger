package com.unifiedledger.android

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
 * face's static loading copy, and must place a node that renders an entry amount inside the measured
 * band, while the app window keeps the bounds the band was cut from.
 *
 * THE ACCEPTED SAMPLE IS THE NEWEST ONE. A run of equal samples that ended before the newest sample is
 * an EARLIER plateau — the loading face, typically — and accepting it would pin the settled state, the
 * completed index and the reading's number to frames the list face has already replaced, while the
 * gate (a finite, positive reading) sees nothing wrong. A candidate is therefore only probed while it
 * ends at the newest sample; anything else keeps sampling.
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
     * The rig's own bound on the ONE `am instrument` it runs: `DEFAULT_TIMEOUT` (2400 s) in
     * `tools/python/android_p802_v8.py`, which kills the session when it expires. The case compares
     * its own elapsed time against it while it is still in the prepare stage, so an exhausted budget
     * reds as an assertion instead of ending as a killed process with no cause on the transcript.
     */
    const val RIG_TIMEOUT_MILLIS = 2_400_000L

    /**
     * What the case still needs after the frozen condition group is placed: the settle loop and the
     * post-settle header read, each bounded by [SETTLE_TIMEOUT_MILLIS], plus a margin for the reading
     * line. A prepare stage that leaves less than this reds on its own budget.
     */
    const val REMAINING_WORK_MILLIS = SETTLE_TIMEOUT_MILLIS * 2 + 60_000L

    /** How many times one condition-area control is attempted before its placement fails. */
    const val CHECK_ATTEMPTS = 3

    /** The bound on one state poll after one injected click on a condition-area control. */
    const val CHECK_TIMEOUT_MILLIS = 4_000L

    /** The state poll's own step. */
    const val CHECK_POLL_MILLIS = 250L

    /** How many polls pass between two forced-current tree reads inside one state poll. */
    const val CHECK_REFRESH_EVERY = 2

    /** How many ancestors a structural control lookup climbs from its label node (flow 3's bound). */
    const val CHECK_ANCESTOR_DEPTH = 5

    /** The one bound on a structural subtree walk (the frozen flow-3 anchor's own bound). */
    const val CHECK_TREE_NODES = 2_000

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
     * The marker of a RENDERED entry amount: the product's one row text builder appends the posting's
     * currency code (`flowRowPostingLineText`, `P503LedgerViewPresentation.kt:437`), and the row feeds
     * that same text to its visible posting lines AND to its accessibility label
     * (`P503OverviewScreen.kt:304-324`, rendered unchanged by the search face at
     * `P503SearchScreen.kt:335`). Every posting of this sample is written in that currency
     * (`P802V8RenderSeed.writeTransactions`), so a node carrying the code renders row content, never a
     * mere layout container.
     */
    val amountMarker: String = CATALOG_MANAGED_CURRENCY.code

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
    private fun annotatedIndexes(): List<Int> = (0 until P802SearchDeviceFacts.TRANSACTION_COUNT).filter { it % P802SearchDeviceFacts.ANNOTATION_STRIDE == 0 }

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
    private fun noteOf(index: Int): String? = if (index % P802SearchDeviceFacts.NOTE_STRIDE == 0) P802SearchDeviceFacts.NOTE_MARKER else null

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
 * result header's total, and the bounds of one node that renders an entry amount and lies inside the
 * measured band.
 *
 * [rowBounds] is the node the band was checked against, and it was selected because it renders an
 * entry amount (`carriesAmountMarker`), so "the measured region is the result list" is a comparison of
 * two rects plus rendered row content, not an assumption about a window percentage.
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
    private val instrumentation: Instrumentation,
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
     * Each placement is verified from the CONTROL'S OWN STATE after the click, so a click the product
     * never received fails here instead of leaving a silently different condition behind. The state is
     * read from a forced-current tree and only the state is the success test ([checkVerified]).
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
        // The keyword is addressed by position because the frozen default window leaves exactly one
        // editable field; `setFieldText` reads the value back, so a rejected write never passes here.
        check(flow.setFieldText(1, P802V8RenderFacts.noteMarker)) {
            "the note keyword must land in the field"
        }
        // The IME is dismissed BEFORE the two control clicks: the semantic click path does not need it,
        // but its coordinate fallback would land on the keyboard, and the pre-submit frame must be free
        // of the IME so the comparison band is the app's own surface.
        check(flow.hideKeyboardIfShown()) { "the input method must be gone after the keyword was typed" }
        check(toggleTag()) { "the tag ${P802V8RenderFacts.tagName} must be checked after the click; see the check-failed line" }
        check(selectMerchant()) { "the merchant ${P802V8RenderFacts.merchantName} must be selected after the click; see the check-failed line" }
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
     *
     * The tree is forced current before the node is located and its bounds are checked to be on
     * screen: the frozen click path falls back to the node's coordinates, and a node read from an
     * earlier traversal can carry bounds that are no longer where it is, so the tap would land on
     * whatever moved underneath it. The click's EFFECT is deliberately NOT awaited here — waiting for
     * the result surface is exactly the interval this reading is about.
     */
    fun submitDispatchNanos(): Long {
        refreshTreeForRead()
        val node =
            checkNotNull(flow.findAppNode { candidate -> flow.descriptionOf(candidate) == P807Q2Flow1Facts.SEARCH_SUBMIT_DESCRIPTION }) {
                "the submit affordance (${P807Q2Flow1Facts.SEARCH_SUBMIT_DESCRIPTION}) must be on this surface"
            }
        val bounds = flow.bounds(node)
        check(node.isEnabled && node.isVisibleToUser) { "the submit affordance must be enabled and visible before the reading" }
        check(isOnScreen(bounds)) { "the submit affordance must lie inside the app window before the reading, saw $bounds" }
        val dispatched = System.nanoTime()
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} submit dispatchNanos=$dispatched bounds=$bounds")
        flow.tap(node)
        log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} submit returnedNanos=${System.nanoTime()}")
        return dispatched
    }

    /**
     * The total the surface RENDERS in its result header (`搜索结果 · 共 N 笔`), or `null` when the list
     * face has not rendered yet. This RE-READS the header after the settle so the reading line can be
     * bound to the count it was measured with; the accept-time region proof is [confirmResultView].
     *
     * A header that has rendered WITHOUT a readable total is a transient, not a verdict: the read is
     * forced current and the poll keeps going, so the frozen copy `搜索结果 · 共 N 笔` is never read as
     * "no result surface" in its first frame.
     */
    fun renderedTotal(deadlineMillis: Long = P802V8RenderFacts.SETTLE_TIMEOUT_MILLIS): Long? {
        val deadline = SystemClock.elapsedRealtime() + deadlineMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            refreshTreeForRead()
            val header = flow.appNodes().firstOrNull { node -> flow.textOf(node).startsWith(P802V8RenderFacts.RESULTS_HEADER) }
            if (header != null) {
                val text = flow.textOf(header)
                val match = RENDERED_TOTAL.find(text)
                val total = match?.groupValues?.get(1)?.toLongOrNull()
                if (total != null) {
                    log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} resultHeader=[$text] bounds=${flow.bounds(header)}")
                    return total
                }
                log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} resultHeader=[$text] carries no total yet")
            }
            SystemClock.sleep(P802V8RenderFacts.SAMPLE_INTERVAL_MILLIS.toLong())
        }
        return null
    }

    /**
     * The accept-time tree gate: a settled pixel state is accepted only when the surface is, at that
     * very instant, the rendered result list.
     *
     * ONE forced-current tree read answers three questions, and all three are needed:
     * 1. the app window still has the bounds the band was cut from, so the band measures the same
     *    window it was derived from (a window that re-laid out mid-reading would silently re-aim it);
     * 2. the result header `搜索结果 · 共 436 笔` has rendered AND the list face's static `正在搜索…` row
     *    is gone — the loading row does not animate, so a settled grid made of it alone would end the
     *    reading early and quietly report a number that is too small;
     * 3. at least one node below that header RENDERS AN ENTRY AMOUNT ([carriesAmountMarker]) and lies
     *    inside the measured band, which turns "the measured region is the result list" into an
     *    assertion about two rects plus rendered row content.
     *
     * A surface that has not got there YET returns `null` and the caller keeps sampling. A band that
     * holds no node carrying an entry amount is a red instead: it is the FAIL-CLOSED guard that the
     * measured region holds something decidable, not a settle invariant — the list face can be on
     * screen for a frame before its rows are laid out, so a red here means the guard fired, not that
     * every later sample would have stayed empty.
     *
     * The tree is forced current FIRST, before the window bounds are read: the window, the header,
     * the absent loading row and the in-band amount row must all be read after ONE forced refresh.
     * Reading the window first let a cached root equal the expected bounds while the refreshed nodes
     * behind it already belonged to the next frame, so the equality was judged on the stale tree.
 *
 * The forced refresh makes the root current at that call, and only there: the two reads behind it
 * each take a root of their own — `windowBounds()` from `flow.ui.root()`, `appNodes()` from
 * `P807Q2Flow1DeviceSupport.kt:1432` by way of `AndroidScaleUi.kt:181` — so a re-layout landing
 * between them decides the window equality on a root one read older than the nodes it is paired
 * with. That is a narrow race, not a closed gap, and the rest of this gate is what bounds it: the
 * amount row must still hold inside the band cut from that window, and a rejected probe keeps
 * sampling, so the next probe (`TREE_PROBE_INTERVAL_NANOS`, 500 ms) re-reads both, and the pixel
 * change behind the settle candidate still has to agree.
     */
    fun confirmResultView(
        expectedWindow: Rect,
        band: Rect,
    ): P802V8ResultView? {
        refreshTreeForRead()
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
        val match = RENDERED_TOTAL.find(text)
        val total = match?.groupValues?.get(1)?.toLongOrNull()
        if (total == null) {
            log("${P802V8RenderFacts.DIAGNOSTIC_PREFIX} settled candidate pending: header [$text] carries no total")
            return null
        }
        val headerBounds = flow.bounds(header)
        val belowHeader =
            nodes.filter { node ->
                val bounds = flow.bounds(node)
                bounds.top >= headerBounds.bottom && bounds.width() > 0 && bounds.height() > 0
            }
        val row =
            checkNotNull(belowHeader.firstOrNull { node -> Rect.intersects(flow.bounds(node), band) && carriesAmountMarker(node) }) {
                "the measured band $band holds no node carrying the entry amount marker " +
                    "${P802V8RenderFacts.amountMarker} below the header [$text]: headerBounds=$headerBounds " +
                    "nearest=${belowHeader.minByOrNull { node -> abs(flow.bounds(node).top - band.top) }?.let { node -> flow.bounds(node) }}"
            }
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} settled view header=[$text] headerBounds=$headerBounds " +
                "rowBounds=${flow.bounds(row)} band=$band",
        )
        return P802V8ResultView(renderedTotal = total, rowBounds = flow.bounds(row))
    }

    /**
     * Whether the node renders an entry's AMOUNT, which is what makes it a result row rather than a
     * container that merely sits in the band: the product's one row text builder appends the posting's
     * currency code to every posting line (`P503LedgerViewPresentation.kt:437`), and the row passes the
     * same text to its visible lines and to its accessibility label (`P503OverviewScreen.kt:304-324`).
     */
    private fun carriesAmountMarker(node: AccessibilityNodeInfo): Boolean {
        val marker = P802V8RenderFacts.amountMarker
        return flow.textOf(node).contains(marker) || flow.descriptionOf(node).contains(marker)
    }

    /** Checks the tag row's box, proving the row's own state flipped; the state is the only test. */
    private fun toggleTag(): Boolean =
        checkVerified(
            step = "tag",
            label = P802V8RenderFacts.tagName,
            expectedState = "checked=true",
            effect = { node -> node.isChecked },
        )

    /**
     * Selects the merchant row's radio, proving the row's own state flipped; same shape as the tag.
     *
     * The row is a Material3 `RadioButton` declared with `selected` for its state and `onClick` for its
     * action (`P503SearchScreen.kt:247`), so the successful state is `selected` — the checkbox row at
     * `P503SearchScreen.kt:211` reports `checked` instead. Both are read from the same node the click
     * was aimed at.
     */
    private fun selectMerchant(): Boolean =
        checkVerified(
            step = "merchant",
            label = P802V8RenderFacts.merchantName,
            expectedState = "checked=true|selected=true",
            effect = { node -> node.isChecked || node.isSelected },
        )

    /**
     * Places ONE condition-area control and proves the surface took it: forced-current read, the
     * row's own toggle node (structural first, the whole tree's band-mates last), ONE semantic click,
     * a bounded poll of the control's own state, and — only after that poll has read the pre-click
     * state for its whole window — ONE coordinate tap on the toggle node's own centre.
     *
     * WHY THE CLICK IS NOT THE EVIDENCE (the first device run red on `applyFixedCondition`). This
     * stack accepts `ACTION_CLICK` without any state change, and `AndroidScaleUi.clickNode` discards
     * the action's answer (`AndroidScaleUi.kt:412-445`); a read served from the client accessibility
     * cache then answers the PRE-click state (`AndroidScaleUi.kt:223-273`, the D-203 signature). So
     * the action's answer is an observation, the CONTROL'S OWN STATE is the only success test, and
     * every read a judgement depends on is forced current first ([refreshTreeForRead]).
     *
     * WHY THE COORDINATE TAP WAITS FOR A FAILED POLL. A checkbox is a TOGGLE: a second injection after
     * a click that did land would UNCHECK the row, and the case would report a refusal it caused. The
     * fallback therefore fires only once the bounded poll has read the pre-click state throughout.
     */
    private fun checkVerified(
        step: String,
        label: String,
        expectedState: String,
        effect: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        var readState = "target=ABSENT"
        var targetBounds: Rect? = null
        var lastPoint: Pair<Int, Int>? = null
        repeat(P802V8RenderFacts.CHECK_ATTEMPTS) { round ->
            if (!flow.ui.has(label)) runCatching { flow.seekApp(label) }
            refreshTreeForRead()
            val target = toggleNear(label)
            val bounds = target?.let { node -> flow.bounds(node) }
            readState = target?.let { node -> describe(node) } ?: "target=ABSENT"
            if (target == null || bounds == null || !isOnScreen(bounds)) {
                log(
                    "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check step=$step round=$round expected=$expectedState " +
                        "read=$readState channel=none reason=no-on-screen-toggle",
                )
                flow.dumpUiSnapshot("$step:round$round")
                return@repeat
            }
            if (effect(target)) {
                log(
                    "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check step=$step round=$round expected=$expectedState " +
                        "read=$readState channel=none reason=already-in-expected-state",
                )
                return true
            }
            val accepted = clickSemantic(target)
            log(
                "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check step=$step round=$round channel=semantic " +
                    "actionClick=$accepted expected=$expectedState read=$readState",
            )
            if (awaitEffect(step, round, "semantic", label, effect)) return true
            val point = bounds.centerX() to bounds.centerY()
            tapAt(point)
            lastPoint = point
            targetBounds = bounds
            log(
                "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check step=$step round=$round channel=coordinate-target " +
                    "point=(${point.first},${point.second}) bounds=$bounds",
            )
            if (awaitEffect(step, round, "coordinate-target", label, effect)) return true
            flow.dumpUiSnapshot("$step:round$round")
            flow.settle()
        }
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check-failed step=$step label=[$label] expected=$expectedState " +
                "read=$readState clickedBounds=${targetBounds ?: "none"} lastPoint=${lastPoint ?: "none"} " +
                "attempts=${P802V8RenderFacts.CHECK_ATTEMPTS}",
        )
        return false
    }

    /**
     * Polls the control's own state for at most [P802V8RenderFacts.CHECK_TIMEOUT_MILLIS], forcing the
     * tree current every [P802V8RenderFacts.CHECK_REFRESH_EVERY] polls: without that, a landed click
     * can stay invisible for the poll's whole window because the client cache still answers the
     * pre-click tree. The first and the last reading are logged; the middle ones are not.
     */
    private fun awaitEffect(
        step: String,
        round: Int,
        channel: String,
        label: String,
        effect: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + P802V8RenderFacts.CHECK_TIMEOUT_MILLIS
        var polls = 0
        var lastRead = "target=ABSENT"
        while (true) {
            if (polls % P802V8RenderFacts.CHECK_REFRESH_EVERY == 0) refreshTreeForRead()
            val target = toggleNear(label)
            lastRead = target?.let { node -> describe(node) } ?: "target=ABSENT"
            val reached = target != null && effect(target)
            polls++
            if (reached) {
                log(
                    "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check-poll step=$step round=$round channel=$channel " +
                        "reached=true polls=$polls elapsedMs=${SystemClock.elapsedRealtime() - started}",
                )
                return true
            }
            if (polls == 1) {
                log(
                    "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check-poll step=$step round=$round channel=$channel " +
                        "reached=false polls=$polls read=$lastRead",
                )
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            SystemClock.sleep(P802V8RenderFacts.CHECK_POLL_MILLIS)
        }
        log(
            "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} check-poll step=$step round=$round channel=$channel " +
                "reached=false polls=$polls elapsedMs=${SystemClock.elapsedRealtime() - started} read=$lastRead",
        )
        return false
    }

    /**
     * Forces the tree current before a state is JUDGED: only a MISS goes through the frozen helper's
     * forcing passes (`root.refresh()` with bypassCache, then the rate-limited cache reset,
     * `AndroidScaleUi.kt:223-273`), while a lookup of a PRESENT text is cache-served and refreshes
     * nothing — which is why the sentinel is a guaranteed miss.
     */
    private fun refreshTreeForRead() {
        flow.ui.has(REFRESH_SENTINEL)
    }

    /**
     * The toggle node of the row that carries [label]: STRUCTURE FIRST — climb the label's parent
     * chain and take the one checkable/selectable node on the label's band — then every toggle in the
     * visible tree on that band, nearest vertical centre last.
     *
     * The label is a TEXT node and a text node is not clickable, so the box is addressed
     * structurally. A Y-only tie-break returns the band-mate of the NEXT row (flow 3's
     * `P807Q2Flow3DeviceSupport.kt:2012-2015`), so several candidates on one band are separated by the
     * horizontal gap to the label, with a toggle to the RIGHT of the label pushed behind every left one.
     */
    private fun toggleNear(label: String): AccessibilityNodeInfo? {
        val labelNode = flow.findAppNode { node -> flow.textOf(node) == label } ?: return null
        val band = flow.bounds(labelNode)
        var container = labelNode.parent
        var depth = 0
        while (depth < P802V8RenderFacts.CHECK_ANCESTOR_DEPTH) {
            val current = container ?: break
            val candidates =
                collectNodes(current).filter { node ->
                    node != labelNode && node.isVisibleToUser && isToggle(node) && onBand(node, band)
                }
            if (candidates.size == 1) return candidates.single()
            if (candidates.isNotEmpty()) return nearestToggle(candidates, band)
            container = current.parent
            depth++
        }
        val toggles = flow.appNodes().filter { node -> isToggle(node) && onBand(node, band) }
        return toggles.minByOrNull { node -> abs(flow.bounds(node).centerY() - band.centerY()) }
    }

    /**
     * Among several toggles sharing [band], the one that belongs to the label's own row: the smallest
     * horizontal gap between the toggle's right edge and the label's left edge, with a toggle to the
     * RIGHT of the label pushed behind every left one by [TOGGLE_RIGHT_OF_LABEL_PENALTY]. The vertical
     * distance only separates rows that a re-measured band lets in.
     */
    private fun nearestToggle(
        toggles: List<AccessibilityNodeInfo>,
        band: Rect,
    ): AccessibilityNodeInfo? {
        val nearest =
            toggles
                .map { node -> node to flow.bounds(node) }
                .filter { (_, rect) -> rect.width() > 0 }
                .minByOrNull { (_, rect) ->
                    val gap = band.left - rect.right
                    val horizontal = if (gap >= 0) gap else TOGGLE_RIGHT_OF_LABEL_PENALTY + (-gap)
                    horizontal + abs(rect.centerY() - band.centerY())
                }
        return nearest?.first
    }

    /**
     * Whether [node] can carry a control's state: checkable, checked or selected, or the platform's
     * radio/check class. A `RadioButton` declares `selected` rather than `checked` as its state
     * (`P503SearchScreen.kt:247`), so an `isCheckable`-only filter cannot see the merchant row's box
     * at all — that is why the predicate keeps all five shapes.
     */
    private fun isToggle(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isCheckable || node.isChecked || node.isSelected || className.endsWith("RadioButton") || className.endsWith("CheckBox")
    }

    /** Whether [node]'s centre sits on [band]'s row, with the slack a re-measured row needs. */
    private fun onBand(
        node: AccessibilityNodeInfo,
        band: Rect,
    ): Boolean {
        val bounds = flow.bounds(node)
        return bounds.centerY() in (band.top - BAND_SLACK_PX)..(band.bottom + BAND_SLACK_PX) && bounds.width() > 0
    }

    /** Whether [bounds] lie inside the display AND the app window, so a coordinate tap can land. */
    private fun isOnScreen(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val metrics = instrumentation.targetContext.resources.displayMetrics
        if (bounds.left >= metrics.widthPixels || bounds.top >= metrics.heightPixels || bounds.right <= 0 || bounds.bottom <= 0) {
            return false
        }
        val window = runCatching { windowBounds() }.getOrNull() ?: return true
        return bounds.left < window.right && window.left < bounds.right && bounds.top < window.bottom && window.top < bounds.bottom
    }

    /**
     * The semantic channel with its answer KEPT: ACTION_CLICK on [node], then on up to
     * [P802V8RenderFacts.CHECK_ANCESTOR_DEPTH] clickable ancestors (the discipline
     * `AndroidScaleUi.clickNode` uses). `false` is an observation, not a failure.
     */
    private fun clickSemantic(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(P802V8RenderFacts.CHECK_ANCESTOR_DEPTH) {
            val candidate = current ?: return false
            val usable = candidate.isEnabled && candidate.isVisibleToUser
            if (usable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return false
    }

    /** Injects one touchscreen tap at [point] (the D-217 coordinate path `AndroidScaleUi.clickNode` uses). */
    private fun tapAt(point: Pair<Int, Int>) {
        val (x, y) = point
        val down = SystemClock.uptimeMillis()
        val events =
            listOf(
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
                MotionEvent.obtain(down, down + TOUCH_HOLD_MILLIS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
            )
        try {
            events.forEach { event ->
                check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "coordinate tap injection refused at ($x, $y)" }
            }
        } finally {
            events.forEach { event -> event.recycle() }
        }
        SystemClock.sleep(180)
    }

    /** Breadth-first walk of [root], bounded by [P802V8RenderFacts.CHECK_TREE_NODES] (flow 3's shape). */
    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < P802V8RenderFacts.CHECK_TREE_NODES) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return result
    }

    /** The node's own shape, so a refused click is diagnosable from the log alone. */
    private fun describe(node: AccessibilityNodeInfo): String =
        "class=${node.className} clickable=${node.isClickable} enabled=${node.isEnabled} visible=${node.isVisibleToUser} " +
            "checkable=${node.isCheckable} checked=${node.isChecked} selected=${node.isSelected} bounds=${flow.bounds(node)}"

    private companion object {
        /** The rendered result header's own copy: `搜索结果 · 共 N 笔`. */
        val RENDERED_TOTAL = Regex(P802V8RenderFacts.RESULTS_HEADER + " · 共 ([0-9]+) 笔")

        /** A guaranteed-miss text whose lookup is what takes the frozen helper's forcing passes. */
        const val REFRESH_SENTINEL = "\u0000P802-V8-refresh"

        /** How far a toggle that sits right OF its label is pushed behind the left ones. */
        const val TOGGLE_RIGHT_OF_LABEL_PENALTY = 10_000

        /** How far a node's centre may sit off the label's band and still count as its row. */
        const val BAND_SLACK_PX = 16

        /** The coordinate tap's hold time (the frozen drivers' own value). */
        const val TOUCH_HOLD_MILLIS = 60L
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
 * One diagnostic line stating where the case stands against the rig's outer budget: the elapsed time
 * since the case started, what is left of [P802V8RenderFacts.RIG_TIMEOUT_MILLIS], and the least the
 * remaining work needs. The case reads it during the prepare stage, so a budget too small to finish
 * reds on an assertion here instead of ending as a process the driver killed.
 */
internal fun p802V8BudgetLine(
    stage: String,
    startedElapsedMs: Long,
    nowElapsedMs: Long,
): String {
    val elapsed = nowElapsedMs - startedElapsedMs
    val remaining = P802V8RenderFacts.RIG_TIMEOUT_MILLIS - elapsed
    return "${P802V8RenderFacts.DIAGNOSTIC_PREFIX} budget stage=$stage elapsedMs=$elapsed " +
        "remainingMs=$remaining rigTimeoutMs=${P802V8RenderFacts.RIG_TIMEOUT_MILLIS} " +
        "remainingWorkMs=${P802V8RenderFacts.REMAINING_WORK_MILLIS}"
}

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
