package com.unifiedledger.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.ImportFilePickResultChannel
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * P8-05 批 c (D-331 spec sections 4 批 c / 5 V-3/V-8/V-9) device-fixture recipe: the synthetic
 * EXPENSE-chain ledger the readable export's on-device readings and full-chain legs walk, built on
 * the device itself.
 *
 * WHY THIS FILE CARRIES NO TEST ANNOTATION: the inventory enumerator takes the FIRST `class`
 * keyword of a file and skips every file without a test annotation (the `P801ChainFixture` /
 * `P802SearchDeviceFixture` precedent), so the six device cases live in
 * `P805ReadableExportDeviceChainInstrumentedTest.kt` and this helper never enters
 * `tools/ci/android-instrumented-inventory.txt`.
 *
 * WHY RAW INSERTS AND NOT THE PER-ROW COMMIT PORTS: the same budget arithmetic the P8-02 fixture
 * documents (the product commit chain rebuilds a prepared statement per call, ~5 ms per fresh
 * prepare against this schema) rules the commit ports out at the 50k tier, and the readings must
 * stay inside the shard's 15-minute instrumentation timeout. This fixture therefore opens the
 * product-created generation file itself and writes the same rows with chunked multi-row INSERTs
 * in ONE transaction (500 rows per statement).
 *
 * THE ROWS ARE THE PRODUCT'S ROWS. The first open is the real composition root
 * (`openAndroidStableStorageLedger`), so the ledger row, the default catalog and the generation
 * file are the product's; the fixture only adds formal rows. Every transaction is a two-leg
 * EXPENSE chain on the default catalog's own accounts — leg 0 posts +amount to the hidden expense
 * account (the default catalog leaf category's posting account, so the batch-a presentation table
 * resolves the 分类腿 exactly as the JVM real-ledger seed does) and leg 1 posts -amount to the
 * owned asset account, so every chain nets to zero. No semantic-table row, no annotation and no
 * note is written for the reading tiers: the export's page source (`ledgerFlowPage`) reaches
 * every chain through the single effective-predicate definition point the product itself joins
 * (`transaction_effective_state`), which is exactly the row shape the batch-a jvmTest seed walks.
 *
 * GENERATION FORMULA (index = 0 until count):
 *  - statistics instant = `base - (index % 30) days`, where `base` is the device wall clock
 *    truncated to the whole second AT FIXTURE CONSTRUCTION, so the numeric projection is exact
 *    (every instant is a whole second) and the two wall-clock columns of the file stay inside one
 *    bounded window.
 *  - amount = 1000 + (index % 97) * 137 minor units (the P8-02 fixture's spread, so no reading is
 *    taken over a degenerate single-amount column).
 *  - note = NULL unless the case passes its own note payload (the mid-write-failure leg passes a
 *    high-entropy padded note per row so the streamed bytes cross the failing provider's window
 *    MID-WALK, not at the first chunk).
 *
 * The fixture also returns the construction manifest facts (the seeded transaction count, the
 * missing-projection count and the schema version) so every case asserts STRUCTURAL facts, never
 * merely non-empty outcomes (the P8-02 device-timing discipline).
 */
internal object P805ReadableExportDeviceFacts {
    /** The product's fixed single-ledger identity (`App.kt`). */
    val ledgerId: LedgerId = LedgerId(PRODUCT_LEDGER_ID)

    /** The 2k-tier synthetic sample size (spec section 4 批 c: 2k 级读数). */
    const val TIER_2K: Int = 2_000

    /** The 50k-tier synthetic sample size (spec section 4 批 c: 50k 级读数). */
    const val TIER_50K: Int = 50_000

    /** Rows per multi-row INSERT statement (the P8-02 fixture's chunk). */
    const val CHUNK: Int = 500

    /** Days the reading instants are spread over (the P8-02 fixture's window). */
    const val SPREAD_DAYS: Int = 30

    /** The amount spread: `AMOUNT_BASE + (index % AMOUNT_STRIDE) * AMOUNT_STEP` minor units. */
    const val AMOUNT_BASE: Long = 1_000L
    const val AMOUNT_STRIDE: Int = 97
    const val AMOUNT_STEP: Long = 137L

    /** The default seed's owned asset account (leg 1) and hidden expense account (leg 0). */
    const val ASSET_ACCOUNT_ID: String = DEFAULT_MANAGEABLE_ACCOUNT_ID
    const val EXPENSE_ACCOUNT_ID: String = DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID

    /**
     * The mid-write-failure leg's note unit: repeated per row so the streamed bytes cross the
     * failing provider's 1 MiB window MID-WALK. The prefix + `P805_NOTE_UNIT.repeat(12)` keeps the
     * per-row note (~860 UTF-8 bytes) well above the per-row budget a 2,000-row walk needs to
     * exceed 1 MiB of sheet bytes long before the sheet closes.
     */
    const val NOTE_PREFIX: String = "p805 中途失败压载行 "
    const val NOTE_UNIT: String = "流出参考号 出入凭证号 审核备注 对账批次号；"

    /** The cached-redirect root prefix, so no other suite's root can collide. */
    const val ROOT_PREFIX: String = "p805-readable-export-"
}

/** One built sample: its opened product graph and its construction manifest facts. */
internal class P805ReadableExportDeviceFixture(
    testName: String,
) : AutoCloseable {
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root: File = File(target.cacheDir, P805ReadableExportDeviceFacts.ROOT_PREFIX + testName)

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

    /** The transactions the raw writer left behind (read back from the file). */
    var transactionCount: Long = 0L
        private set

    /** Rows whose numeric statistics projection is still NULL; must stay 0 or the read fails. */
    var missingProjectionRows: Long = 0L
        private set

    /** The generation file's `PRAGMA user_version` (the frozen schema version). */
    var schemaVersion: Long = 0L
        private set

    /**
     * D7-11's build shape (the P8-02 fixture precedent): build through the product factory, close
     * it, write the synthetic rows on this fixture's own connection, then reopen through the
     * product factory. The reopened driver name is asserted to be the file this fixture wrote, so
     * a reading can never come from another generation.
     */
    fun build(
        transactionCount: Int,
        noteOf: ((Int) -> String?)? = null,
    ) {
        check(driverNames.isEmpty()) { "the fixture builds exactly once" }
        openRegisteredGraph().close()
        writeFixtureRows(driverNames.single(), transactionCount, noteOf)
        graph = openRegisteredGraph()
        check(driverNames.size == 2 && driverNames[0] == driverNames[1]) {
            "the reopen must land on the file this fixture wrote: $driverNames"
        }
        check(this.transactionCount == transactionCount.toLong()) {
            "the sample must hold $transactionCount transactions, found ${this.transactionCount}"
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
    private fun silentImportPort(): AndroidImportFilePickPort<Uri> =
        AndroidImportFilePickPort<Uri>(
            launchOpenDocument = {},
            resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
            openInputStream = { null },
            onResult = {},
        )

    // ------------------------------------------------------------------ the raw write

    private fun writeFixtureRows(
        driverName: String,
        count: Int,
        noteOf: ((Int) -> String?)?,
    ) {
        val mainFile = File(driverName)
        check(mainFile.isFile) { "the product open must have created the generation file: $driverName" }
        SQLiteDatabase.openDatabase(mainFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            val preexisting = longQuery(database, "SELECT count(*) FROM ledger_transaction")
            check(preexisting == 0L) { "the product's fresh ledger must start with no transactions, found $preexisting" }
            database.beginTransaction()
            try {
                writeTransactions(database, count, noteOf)
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

    private fun writeTransactions(
        database: SQLiteDatabase,
        count: Int,
        noteOf: ((Int) -> String?)?,
    ) {
        val ledger = P805ReadableExportDeviceFacts.ledgerId.value
        val facts = P805ReadableExportDeviceFacts
        insertChunked(
            database,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ",
            count,
        ) { index -> "('${transactionId(index)}', '$ledger', 'EXPENSE', NULL)" }
        insertChunked(
            database,
            "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ",
            count,
        ) { index -> "('${postingSetId(index)}', '$ledger')" }
        insertChunked(
            database,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, " +
                "occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) VALUES ",
            count,
        ) { index ->
            val instant = statisticsInstant(index)
            val text = instant.toString()
            "('${versionId(index)}', '${transactionId(index)}', '$ledger', 1, '${postingSetId(index)}', " +
                "'$text', '$text', '$text', ${sqlLiteral(noteOf?.invoke(index))}, ${projectionNanos(instant)})"
        }
        insertChunked(
            database,
            "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, " +
                "currency_code, currency_precision) VALUES ",
            count * POSTINGS_PER_CHAIN,
        ) { ordinal ->
            val index = ordinal / POSTINGS_PER_CHAIN
            val leg = ordinal % POSTINGS_PER_CHAIN
            val amount = facts.AMOUNT_BASE + (index % facts.AMOUNT_STRIDE) * facts.AMOUNT_STEP
            val (account, signedAmount) =
                if (leg == 0) facts.EXPENSE_ACCOUNT_ID to amount else facts.ASSET_ACCOUNT_ID to -amount
            "('${postingId(index, leg)}', '${postingSetId(index)}', '$ledger', $leg, '$account', $signedAmount, " +
                "'${CATALOG_MANAGED_CURRENCY.code}', ${CATALOG_MANAGED_CURRENCY.precision})"
        }
        insertChunked(
            database,
            "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ",
            count,
        ) { index -> "('${transactionId(index)}', '$ledger', '${versionId(index)}')" }
    }

    // ------------------------------------------------------------------ shared row helpers

    /** The `index`-th statistics instant: `base - (index % SPREAD_DAYS) days`, whole seconds. */
    private fun statisticsInstant(index: Int): Instant = base - (index % P805ReadableExportDeviceFacts.SPREAD_DAYS).days

    private fun transactionId(index: Int): String = "tx-${(index + 1).toString().padStart(5, '0')}"

    private fun postingSetId(index: Int): String = "${transactionId(index)}-posting-set-1"

    private fun versionId(index: Int): String = "${transactionId(index)}-version-1"

    private fun postingId(
        index: Int,
        leg: Int,
    ): String = "${transactionId(index)}-posting-$leg"

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
            val end = minOf(start + P805ReadableExportDeviceFacts.CHUNK, count)
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
