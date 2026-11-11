package com.unifiedledger.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_NAME
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.ImportFilePickResultChannel
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * P8-02 i2b-2 V-8 device-fixture recipe (ruling D7-11; spec
 * `docs/specs/2026-11-11-p8-02-search-filter-design.md` section 5 V-8): the 61,000-TRANSACTION
 * sample the device port-timing reading needs, built on the device itself.
 *
 * WHY THIS FILE CARRIES NO TEST ANNOTATION: the inventory enumerator takes the FIRST `class`
 * keyword of a file and skips every file without a test annotation, so the fixture never enters
 * `tools/ci/android-instrumented-inventory.txt`; the one device case lives in
 * `P802SearchDeviceTimingInstrumentedTest.kt`.
 *
 * WHY NOT THE PER-ROW WRITERS: the product commit ports rebuild a prepared statement per call.
 * `P705Harness` measures that path at roughly 5 ms per fresh prepare against this schema (502
 * triggers, 449 indexes), so 61k transactions at six statements each is about thirty minutes —
 * over both the 20-minute attempt budget and the workflow's 15-minute instrumentation timeout.
 * This fixture therefore opens the product-created generation file itself and writes the same rows
 * with chunked multi-row INSERTs in ONE transaction (500 rows per statement).
 *
 * THE ROWS ARE THE PRODUCT'S ROWS. The first open is the real composition root
 * (`openAndroidStableStorageLedger`), so the ledger row, the default catalog and the generation
 * file are the product's; the fixture only adds formal rows. Every transaction is a two-leg
 * EXPENSE chain on the default catalog's own accounts (the hidden expense account plus the owned
 * asset account; this product seed has no hidden income account, so the JVM fixture's
 * EXPENSE/INCOME alternation would need a catalog write without changing anything the search
 * read can observe).
 *
 * GENERATION FORMULA (index = 0 until 61,000):
 *  - statistics instant = `base - (index % 30) days`, where `base` is the device wall clock
 *    truncated to the whole second AT FIXTURE CONSTRUCTION. Every instant therefore lands in the
 *    last 30 days, inside the search read's default 90-day window, and the numeric projection is
 *    exact because every instant is a whole second. Fixed absolute instants would fall out of the
 *    rolling window and the read would answer 0 rows without failing.
 *  - note = "咖啡标记" on every 7th index, NULL otherwise (the note arm's manifest: 8,715 rows).
 *  - a current annotation (tag-00 plus merchant-0/merchant-1 alternating) on every 70th index
 *    (the merchant arm's manifest: 436 rows for merchant-0), with a matching
 *    `catalog_tag`/`catalog_merchant`/current-name set so the keyword resolves.
 *  - seven transactions carry one posting-semantic category row each, one in every one of the
 *    seven `rgXX_posting_semantic` tables, against the default seed's leaf category (the category
 *    arm's manifest: 7 rows).
 *  - amount = 1000 + (index % 97) * 137 minor units; leg 0 posts +amount to the hidden expense
 *    account, leg 1 posts -amount to the owned asset account, so every chain nets to zero.
 *
 * THE PROJECTION IS BACK-FILLED IN THE SAME INSERT (`statistics_at_epoch_nanos`), because a NULL
 * projection makes the search read answer `SEARCH_MISSING_PROJECTION` instead of a page; there is
 * no later UPDATE pass to skip.
 *
 * The fixture also returns the construction MANIFESTS (the expected matched ids per arm, in the
 * read's keyset order) so the test asserts sets, never merely non-empty pages.
 */
internal object P802SearchDeviceFacts {
    /** The product's fixed single-ledger identity (`App.kt`). */
    val ledgerId: LedgerId = LedgerId(PRODUCT_LEDGER_ID)

    /** The vector's sample size: TRANSACTIONS, not import candidates (spec section 5 V-8). */
    const val TRANSACTION_COUNT: Int = 61_000

    /** The note arm's stride: every 7th transaction carries [NOTE_MARKER]. */
    const val NOTE_STRIDE: Int = 7

    /** The annotation stride: every 70th transaction carries the current merchant/tag annotation. */
    const val ANNOTATION_STRIDE: Int = 70

    /** Rows per multi-row INSERT statement. */
    const val CHUNK: Int = 500

    /** Days the 61k instants are spread over; must stay below the read's 90-day default window. */
    const val SPREAD_DAYS: Int = 30

    const val NOTE_MARKER: String = "咖啡标记"

    const val TAG_ID: String = "tag-00"
    const val TAG_NAME: String = "标签零"
    const val MERCHANT_ZERO_ID: String = "merchant-0"

    /** Merchant names in position order: `merchant-<position>`; merchant-0 is the timed one. */
    val MERCHANT_NAMES: List<String> = listOf("商家零", "商家一")

    /** The default seed's leaf category, its keyword and the two default accounts the legs use. */
    const val CATEGORY_ID: String = DEFAULT_EXPENSE_LEAF_ID
    const val CATEGORY_KEYWORD: String = DEFAULT_EXPENSE_LEAF_NAME
    const val ASSET_ACCOUNT_ID: String = DEFAULT_MANAGEABLE_ACCOUNT_ID
    const val EXPENSE_ACCOUNT_ID: String = DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID

    /** One transaction per semantic table, so every table has a row the category arm can reach. */
    val SEMANTIC_INDEXES: Map<String, Int> =
        mapOf(
            "rg03_transfer_posting_semantic" to 11,
            "rg04_posting_semantic" to 12,
            "rg05_posting_semantic" to 13,
            "rg06_posting_semantic" to 14,
            "rg07_posting_semantic" to 15,
            "rg11_posting_semantic" to 16,
            "rg12_posting_semantic" to 10,
        )

    /** The cached-redirect root prefix, so no other suite's root can collide. */
    const val ROOT_PREFIX: String = "p802-v8-device-"
}

/** One built sample: its counts, its construction manifests and the two arm windows. */
internal class P802SearchDeviceFixture(
    testName: String,
) : AutoCloseable {
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root: File = File(target.cacheDir, P802SearchDeviceFacts.ROOT_PREFIX + testName)

    /**
     * The P8-01a redirect guard, reused unchanged: the production entry resolves its whole storage
     * layout from `getDatabasePath`, so this wrapper keeps every file of this sample out of the
     * real production `databases/` tree (there is exactly one such guard, not a second copy).
     */
    private val context: Context = p801RedirectedContext(root, target)

    /** The device clock truncated to the whole second; every generated instant is relative to it. */
    private val base: Instant = Instant.fromEpochSeconds(System.currentTimeMillis() / 1000L)

    private val driverNames = mutableListOf<String>()

    /** The opened product graph; valid only after [build]. */
    lateinit var graph: CloseableLedgerGraph
        private set

    /** The 61,000 transactions the raw writer left behind (read back from the file). */
    var transactionCount: Long = 0L
        private set

    /** Rows whose numeric statistics projection is still NULL; must stay 0 or the read fails. */
    var missingProjectionRows: Long = 0L
        private set

    /** The generation file's `PRAGMA user_version` (the frozen schema version). */
    var schemaVersion: Long = 0L
        private set

    /** The oldest statistics instant this sample holds (`base - 29 days`). */
    val windowStartInclusive: Instant
        get() = base - (P802SearchDeviceFacts.SPREAD_DAYS - 1).days

    /** A half-open bound covering the newest instant this sample holds (whole seconds). */
    val windowEndExclusive: Instant
        get() = base + 1.seconds

    /** Every transaction carrying the note marker, in the read's keyset order. */
    val noteMatchedIds: List<String> by lazy {
        keysetOrder((0 until P802SearchDeviceFacts.TRANSACTION_COUNT).filter { it % P802SearchDeviceFacts.NOTE_STRIDE == 0 })
    }

    /** Every transaction whose current annotation names merchant-0, in keyset order. */
    val merchantMatchedIds: List<String> by lazy {
        keysetOrder(
            annotatedIndexes().filter { it / P802SearchDeviceFacts.ANNOTATION_STRIDE % P802SearchDeviceFacts.MERCHANT_NAMES.size == 0 },
        )
    }

    /** The seven semantic-table rows the category keyword resolves, in keyset order. */
    val categoryMatchedIds: List<String> by lazy { keysetOrder(P802SearchDeviceFacts.SEMANTIC_INDEXES.values.toList()) }

    /**
     * D7-11: build through the product factory, close it, write the 61k rows on this fixture's own
     * connection, then reopen through the product factory. The reopened driver name is asserted to
     * be the file this fixture wrote, so the reading can never come from another generation.
     */
    fun build() {
        check(driverNames.isEmpty()) { "the fixture builds exactly once" }
        openRegisteredGraph().close()
        writeFixtureRows(driverNames.single())
        graph = openRegisteredGraph()
        check(driverNames.size == 2 && driverNames[0] == driverNames[1]) {
            "the reopen must land on the file this fixture wrote: $driverNames"
        }
        check(transactionCount == P802SearchDeviceFacts.TRANSACTION_COUNT.toLong()) {
            "the sample must hold ${P802SearchDeviceFacts.TRANSACTION_COUNT} transactions, found $transactionCount"
        }
        check(missingProjectionRows == 0L) { "every version row must carry its statistics projection" }
    }

    /** Closes the reopened graph and removes this case's whole ledger root. */
    override fun close() {
        if (::graph.isInitialized) graph.close()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ the product open

    private fun openRegisteredGraph(): CloseableLedgerGraph =
        openAndroidStableStorageLedger(
            context = context,
            importFilePickPort = silentImportPort(),
            importPickChannel = ImportFilePickResultChannel(),
            openDriver = { name ->
                createAndroidLedgerDatabase(context, name).also { driverNames += name }
            },
        )

    /** The silent SAF port the open sequence requires; this fixture never launches a picker. */
    private fun silentImportPort(): AndroidImportFilePickPort<android.net.Uri> =
        AndroidImportFilePickPort<android.net.Uri>(
            launchOpenDocument = {},
            resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
            openInputStream = { null },
            onResult = {},
        )

    // ------------------------------------------------------------------ the raw write

    private fun writeFixtureRows(driverName: String) {
        val mainFile = File(driverName)
        check(mainFile.isFile) { "the product open must have created the generation file: $driverName" }
        SQLiteDatabase.openDatabase(mainFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            val preexisting = longQuery(database, "SELECT count(*) FROM ledger_transaction")
            check(preexisting == 0L) { "the product's fresh ledger must start with no transactions, found $preexisting" }
            database.beginTransaction()
            try {
                writeCatalogItems(database)
                writeTransactions(database)
                writeAnnotations(database)
                writeSemanticRows(database)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            transactionCount = longQuery(database, "SELECT count(*) FROM ledger_transaction")
            missingProjectionRows =
                longQuery(database, "SELECT count(*) FROM transaction_version WHERE statistics_at_epoch_nanos IS NULL")
            schemaVersion = longQuery(database, "PRAGMA user_version")
        }
    }

    private fun writeTransactions(database: SQLiteDatabase) {
        val ledger = P802SearchDeviceFacts.ledgerId.value
        val facts = P802SearchDeviceFacts
        insertChunked(
            database,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ",
            facts.TRANSACTION_COUNT,
        ) { index ->
            // rg11's ownership guard admits an `expense` semantic row only on a PREPAID_RECOGNITION
            // transaction (the JVM fixture's UPDATE, folded into the insert).
            val canonical = if (index == facts.SEMANTIC_INDEXES.getValue("rg11_posting_semantic")) "'PREPAID_RECOGNITION'" else "NULL"
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
            val instant = statisticsInstant(index)
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

    private fun writeAnnotations(database: SQLiteDatabase) {
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
                "'request-${transactionId(index)}', '${statisticsInstant(index)}')"
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
     * The tag/merchant directory the keyword arms resolve against: the default seed carries no
     * items, so the fixture writes the same rows the product's own create command writes.
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
    private fun writeSemanticRows(database: SQLiteDatabase) {
        val ledger = P802SearchDeviceFacts.ledgerId.value
        val category = P802SearchDeviceFacts.CATEGORY_ID
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
                            "'${statisticsInstant(index)}', '${statisticsInstant(index)}')",
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

    // ------------------------------------------------------------------ construction manifests

    /** Every transaction carrying an annotation (the merchant arm's denominator), ascending. */
    private fun annotatedIndexes(): List<Int> = (0 until P802SearchDeviceFacts.TRANSACTION_COUNT).filter { it % P802SearchDeviceFacts.ANNOTATION_STRIDE == 0 }

    /** The sample's read order: `(statistics_at_epoch_nanos DESC, transaction_id DESC)`. */
    private fun keysetOrder(indices: List<Int>): List<String> =
        indices
            .sortedWith(compareByDescending<Int> { statisticsInstant(it) }.thenByDescending { transactionId(it) })
            .map(::transactionId)

    /** The `index`-th statistics instant: `base - (index % SPREAD_DAYS) days`, whole seconds. */
    private fun statisticsInstant(index: Int): Instant = base - (index % P802SearchDeviceFacts.SPREAD_DAYS).days

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
            check(cursor.moveToFirst()) { "the fixture probe returned no row: $sql" }
            cursor.getLong(0)
        }

    /** A SQL string literal, or `NULL` for an absent value. */
    private fun sqlLiteral(value: String?): String = if (value == null) "NULL" else "'${value.replace("'", "''")}'"

    private companion object {
        const val POSTINGS_PER_CHAIN = 2
    }
}
