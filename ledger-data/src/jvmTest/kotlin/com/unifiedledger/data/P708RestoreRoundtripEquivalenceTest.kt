package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetIdSource
import com.unifiedledger.application.BudgetRequestId
import com.unifiedledger.application.BudgetRequestIdSource
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogEntityIdSource
import com.unifiedledger.application.CatalogEntityIds
import com.unifiedledger.application.CatalogManagementRequestIdSource
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.CounterpartyCommandResult
import com.unifiedledger.application.EntryPinResult
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.SaveBudgetConfiguration
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlinx.datetime.YearMonth
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-08 08.D A04/A08 restore round-trip evidence (spec sections 4.3/6.1; plan :181 P706-A01
 * "往返逐 owner/稳定 ID 等价…不只数交易行" and :302/:307; the acceptance registration belongs to the
 * main agent — nothing here records a vector PASS). This class closes the two latest conditional-
 * admission rings of the restore whitelist: **R-221-2 (v34)** and the **R-218-3 chain's newest two
 * links (v33)**. v1/v32 and older rings stay registered to their original batches and are NOT
 * claimed here.
 *
 * The supply chain is the real one (BackupSnapshotDriverTest.kt:27 precedent): a seeded library
 * (catalog command receipt + multi-revision annotation history with its current pointer + a manual
 * expense request row carrying the annotation columns + a three-revision budget chain — two limit
 * revisions plus a CLOSED one — written through the real claim-first command port) is copied out
 * with `VACUUM INTO`, read back
 * through the isolated-strict-migration path, and compared TABLE BY TABLE, VALUE BY VALUE — every
 * 08.A owner table (the 11 tables D-218 §3 froze), the four `manual_*_request` annotation columns,
 * the receipt tables, the four 07.B budget tables, the six `catalog_*` owners, `posting_set`, the
 * P7-02 `entry_pin`/`counterparty` owners, and the base formal transaction/version/posting rows.
 * The budget leg supplies the "预算配置历史"
 * owner half of P706-A11 ("最终 schema 含预算配置历史及标签/商家注释历史…所有新增 owner 往返"); it
 * registers no vector PASS. Three legs:
 *
 *  - (a) v35 direct: the snapshot's authoritative version equals the current schema
 *    (`needsMigration == false`) and the read-back is value-identical;
 *  - (b) v34 → v35 (the `34.sqm` edge): the manual_*_request annotation columns are NULL on the
 *    legacy rows (the frozen "no annotation" default) and every other owner row is value-identical;
 *  - (c) v33 → v35 (the full chain through `33.sqm` + `34.sqm`): the pre-v34 rows survive
 *    value-for-value and the 11 08.A tables land EMPTY (zero backfill).
 *
 * Budget tables exist from v33 onward (`32.sqm` is the v32 → v33 edge and neither `33.sqm` nor
 * `34.sqm` touches them), so every leg carries a seeded budget chain and compares it value-for-value.
 * Asserting them EMPTY on the v33 leg would be an "empty vs empty" vacuous claim, not evidence. Two
 * chains are seeded through the real claim-first command port: a TOTAL chain whose three revisions
 * end CLOSED (so both legal `budget_settings_history` row shapes — MONITORED with a non-null limit
 * and CLOSED with NULL — are covered) and an independent CATEGORY chain on the `cat-local` expense
 * leaf (two MONITORED revisions). The CATEGORY chain needs a bootstrapped `catalog_category` FK
 * target, so every stage first runs the real product catalog bootstrap with [restoreCatalogSeed];
 * that seed carries every id the fixture already references, because bootstrap fails closed on an
 * unresolved posting/manual reference (`SqlDelightCatalogStore.unresolvedReferences`).
 *
 * Contract honesty (R-221-3 precedent): the v34/v33 fixtures are reverse-engineered from the fresh
 * schema (DROP COLUMN / DROP TABLE), so the legs prove the `columnInfo` contract and VALUE
 * equivalence, NOT byte-equality with a real historical v34/v33 file.
 */
class P708RestoreRoundtripEquivalenceTest {
    private val ledgerId = "ledger-restore-roundtrip"

    /** The 08.A owner tables (D-218 §3), compared value-for-value on every leg that has them. */
    private val annotationOwnerTables =
        listOf(
            "catalog_item_version",
            "catalog_tag",
            "catalog_merchant",
            "catalog_item_name_history",
            "catalog_item_command_request",
            "catalog_item_command_receipt",
            "transaction_annotation_revision",
            "transaction_annotation_tag",
            "transaction_annotation_current",
            "transaction_annotation_command_request",
            "transaction_annotation_command_receipt",
        )

    private val manualRequestTables =
        listOf(
            "manual_expense_request",
            "manual_income_request",
            "manual_transfer_request",
            "manual_lending_request",
        )

    /**
     * The base ledger owners the fixture seeds at every stage ([seedCurrentLibrary]): the formal
     * transaction, its version, its postings and its current-version pointer. Neither `33.sqm` nor
     * `34.sqm` touches them, so every leg compares them rather than only the v33 chain.
     */
    private val preV34SurvivorTables =
        listOf("ledger_transaction", "transaction_version", "posting", "ledger_transaction_current_version")

    /**
     * The catalog and posting-set owners the fixture writes but the earlier comparisons never
     * covered (plan :181 P706-A01: the round-trip must be equal "逐 owner/稳定 ID", not just for the
     * annotation and budget families). Each of them is a stable-id owner the restore has to carry
     * value for value.
     *
     * Where the rows come from: the six `catalog_*` tables are written by the real product paths
     * the fixture already runs — [restoreCatalogSeed] through `SqlDelightCatalogStore.bootstrap`
     * writes `catalog_account`, `catalog_category`, `catalog_name_history` and `catalog_version`,
     * and [seedCatalogCommandChain] adds one accepted rename through the same claim-first port,
     * which writes `catalog_command_request`, `catalog_command_receipt` and one more name-history
     * revision. `posting_set` is hand-inserted once, next to the single seeded transaction version
     * ([seedCurrentLibrary]).
     */
    private val catalogOwnerTables =
        listOf(
            "catalog_version",
            "catalog_account",
            "catalog_category",
            "catalog_name_history",
            "catalog_command_request",
            "catalog_command_receipt",
            "posting_set",
        )

    /**
     * The 07.B budget owner tables (P706-A11's "预算配置历史" half). `32.sqm` creates them on the
     * v32 → v33 edge, so they exist on EVERY leg seeded here; the restore must carry the whole
     * chain — config row, claim-first request, immutable settings history and receipt.
     */
    private val budgetOwnerTables =
        listOf(
            "budget_config",
            "budget_command_request",
            "budget_settings_history",
            "budget_command_receipt",
        )

    /**
     * The manual-expense confirmation receipt. The fixture writes it on EVERY leg (it shares the
     * `manual_expense_request` row, whose annotation columns `34.sqm` adds), and no later edge
     * touches the table, so it is compared on every leg rather than only the fresh one.
     */
    private val confirmedExpenseReceiptTables = listOf("confirmed_expense_receipt")

    /**
     * The P7-02 entry-preference and counterparty owners. `entry_pin` is the plan's `:181` "置顶"
     * half; the counterparty pair is the `对手方` family the same "往返逐 owner/稳定 ID 等价"
     * requirement covers (the :181 row itself does not enumerate it). `28.sqm` (v28 → v29) creates
     * all three and no later edge touches them, so they exist on every leg. Both are written through
     * their real product ports ([seedEntryPreferenceAndCounterpartyChain]), not hand-built.
     */
    private val preferenceAndCounterpartyTables =
        listOf("entry_pin", "counterparty", "counterparty_name_history")

    /**
     * Every owner table the fixture writes, compared with `SELECT *` on the leg whose source and
     * snapshot are both at the current schema (v35). One list keeps the legs from drifting apart.
     */
    private val currentSchemaOwnerTables =
        preV34SurvivorTables + annotationOwnerTables + manualRequestTables + budgetOwnerTables +
            catalogOwnerTables + confirmedExpenseReceiptTables + preferenceAndCounterpartyTables

    /**
     * The v34 and rollback owner set: everything in [currentSchemaOwnerTables] except the
     * `manual_*_request` tables. On those legs the source lacks the two annotation columns `34.sqm`
     * adds, so a `SELECT *` would compare different column counts; the legs compare
     * [manualRequestTables] separately on [commonManualExpenseColumns]. The 11 annotation tables DO
     * exist in a v34 source (`33.sqm` created them), so they stay in.
     */
    private val migratedOwnerTables =
        preV34SurvivorTables + annotationOwnerTables + budgetOwnerTables + catalogOwnerTables +
            confirmedExpenseReceiptTables + preferenceAndCounterpartyTables

    /**
     * The v33 owner set. A Stage.V33 source has the 11 `08.A` annotation tables DROPPED
     * ([seedCurrentLibrary]), so they cannot be read from it at all; the leg instead proves they
     * land empty on the migrated copy and compares the remaining owners value-for-value.
     */
    private val preAnnotationEdgeOwnerTables =
        preV34SurvivorTables + budgetOwnerTables + catalogOwnerTables + confirmedExpenseReceiptTables +
            preferenceAndCounterpartyTables

    /** The stable budget identity the seeded chain binds: two limit revisions then a CLOSED one. */
    private val budgetId = BudgetId("budget-roundtrip")

    /** The independent CATEGORY-scope chain: two MONITORED revisions on the `cat-local` leaf. */
    private val categoryBudgetId = BudgetId("budget-roundtrip-category")

    /** The stable expense leaf both the manual-expense row and the CATEGORY budget name. */
    private val categoryLocal = CategoryId("cat-local")

    /**
     * The manual_*_request columns shared by v34 and v35 shapes: the value comparison across the
     * `34.sqm` edge projects onto these (the edge ADDS the two annotation columns, so a `SELECT *`
     * would compare different column counts — the annotation columns are asserted NULL separately).
     */
    private val commonManualExpenseColumns =
        "ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker"

    @Test
    fun aV35SnapshotReadsBackValueIdenticalWithoutMigration() {
        val dir = tempDir()
        val source = File(dir, "source.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V35)
            val snapshot = File(dir, "snapshot-v35")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            // Leg (a): the snapshot is already at the current schema — nothing to migrate.
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}").use { snapshotDriver ->
                assertEquals(LedgerDatabase.Schema.version, readAuthoritativeUserVersionOn(snapshotDriver))
                assertValueIdentical(
                    source.path,
                    snapshot.path,
                    currentSchemaOwnerTables,
                )
                // The 08.D read path returns the whole history off the restored snapshot.
                val database = LedgerDatabase(snapshotDriver)
                val history =
                    SqlDelightTransactionAnnotationStore
                        .forPlatformConfiguredDatabase(database)
                        .loadHistory(LedgerId(ledgerId), TransactionId("tx-annotated"))
                assertEquals(listOf(1L, 2L), history!!.map { it.annotationRevision })
                assertEquals(listOf("tag-a"), history[0].tagIds.map { it.value })
                assertEquals("merchant-1", history[0].merchantId?.value)
                assertEquals(0, history[1].tagIds.size)
                // P706-A11's "预算配置历史" half: the restored snapshot must return the IMMUTABLE
                // revision chain, not merely the current config row. Revision 1's superseded limit
                // and the CLOSED revision's NULL limit surviving next to the current revision is
                // what proves the history crossed the roundtrip. Both legal row shapes of
                // budget_settings_history are covered (spec section 3.3: MONITORED carries a
                // non-null limit, CLOSED carries NULL — close appends, it never deletes).
                assertBothBudgetHistoriesReadBack(snapshotDriver)
                assertCatalogOwnersReadBackNonVacuously(snapshotDriver)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aV34SnapshotMigratesStrictlyAndReadsBackValueIdenticalWithNullAnnotationColumns() {
        val dir = tempDir()
        val source = File(dir, "source-v34.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V34)
            val snapshot = File(dir, "snapshot-v34")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}", foreignKeyProperties()).use { isolated ->
                val migrated = assertIs<StrictMigrationResult.Migrated>(migrateIsolatedSnapshotStrictlyOn(isolated, 34, setOf(1L, 31L, 32L, 33L, 34L)))
                assertEquals(34L, migrated.fromVersion)
                assertEquals(LedgerDatabase.Schema.version, migrated.targetVersion)
                // Every owner row survives value-for-value; the manual_expense_request legacy
                // row's annotation columns are NULL == the frozen "no annotation" default
                // (D-221 R2) and the three unseeded manual tables stay empty. `34.sqm` does not
                // touch the budget tables, so the seeded chain crosses this edge value-for-value.
                assertValueIdentical(
                    source.path,
                    snapshot.path,
                    migratedOwnerTables,
                )
                assertBothBudgetHistoriesReadBack(isolated)
                assertCatalogOwnersReadBackNonVacuously(isolated)
                assertValueIdentical(
                    source.path,
                    snapshot.path,
                    manualRequestTables,
                ) { table -> if (table == "manual_expense_request") commonManualExpenseColumns else null }
                manualRequestTables.forEach { table ->
                    val expectedRows = if (table == "manual_expense_request") 1L else 0L
                    assertEquals(expectedRows, countOn(isolated, "SELECT count(*) FROM $table"), table)
                }
                assertEquals(
                    1L,
                    countOn(isolated, "SELECT count(*) FROM manual_expense_request WHERE annotation_tag_ids IS NULL AND annotation_merchant_id IS NULL"),
                    "the legacy row must read as the frozen no-annotation default",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aV33SnapshotMigratesThroughTheFullChainWithZeroBackfillAndSurvivingRows() {
        val dir = tempDir()
        val source = File(dir, "source-v33.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V33)
            val snapshot = File(dir, "snapshot-v33")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}", foreignKeyProperties()).use { isolated ->
                assertIs<StrictMigrationResult.Migrated>(migrateIsolatedSnapshotStrictlyOn(isolated, 33, setOf(1L, 31L, 32L, 33L)))
                // The 11 08.A tables land as the fresh schema (columnInfo contract) and EMPTY —
                // a purely additive chain never invents annotation rows (zero backfill).
                val fresh = Files.createTempFile("p708-roundtrip-fresh-", ".db")
                try {
                    JdbcSqliteDriver("jdbc:sqlite:${fresh.absolutePathString()}", foreignKeyProperties()).use { freshDriver ->
                        LedgerDatabase.Schema.create(freshDriver)
                        annotationOwnerTables.forEach { table ->
                            assertEquals(columnInfo(freshDriver, table), columnInfo(isolated, table), "$table fresh-vs-migrated columnInfo drift")
                            assertEquals(0L, countOn(isolated, "SELECT count(*) FROM $table"), table)
                        }
                        // The budget tables are NOT part of the zero-backfill claim: `32.sqm`
                        // created them on the v32 -> v33 edge and neither later edge touches
                        // them, so a v33 file ALREADY carries the seeded chain. Asserting them
                        // empty here would be an "empty vs empty" vacuous claim; the VALUES are
                        // compared below. Note the shape check below is a DRIFT GUARD only: the
                        // fixture builds these tables from the fresh schema (no edge re-creates
                        // them), unlike the 11 annotation tables that `33.sqm` really creates.
                        budgetOwnerTables.forEach { table ->
                            assertEquals(columnInfo(freshDriver, table), columnInfo(isolated, table), "$table fresh-vs-migrated columnInfo drift")
                        }
                    }
                } finally {
                    Files.deleteIfExists(fresh)
                }
                // The pre-v34 owner rows survive value-for-value through the whole chain (the
                // manual claim row projected onto its v33 columns — the edge added the two
                // annotation columns). The budget chain was already present at v33 and must
                // cross the untouched edges unchanged. The 11 annotation tables are deliberately
                // NOT in this list: a v33 source does not have them (see
                // [preAnnotationEdgeOwnerTables]).
                assertValueIdentical(source.path, snapshot.path, preAnnotationEdgeOwnerTables)
                // The migrated copy also READS BACK both immutable chains through the real store.
                assertBothBudgetHistoriesReadBack(isolated)
                assertCatalogOwnersReadBackNonVacuously(isolated)
                // Non-vacuity guard for the budget leg: every seeded revision really is on the
                // migrated copy, so the comparison above compares data rather than two empty
                // results. Two chains: the TOTAL chain's three revisions (two limits plus the
                // close) and the CATEGORY chain's two MONITORED revisions. `commitOnce` writes
                // exactly one receipt and one request row per ACCEPTED command, so both tables
                // hold 5 too, and exactly two stable identities exist.
                assertEquals(
                    5L,
                    countOn(isolated, "SELECT count(*) FROM budget_settings_history"),
                    "both seeded budget chains must survive the chain",
                )
                assertEquals(
                    1L,
                    countOn(isolated, "SELECT count(*) FROM budget_settings_history WHERE status = 'CLOSED' AND limit_minor IS NULL"),
                    "the CLOSED NULL-limit row shape must survive the chain",
                )
                assertEquals(
                    5L,
                    countOn(isolated, "SELECT count(*) FROM budget_command_receipt"),
                    "the budget receipts must survive the chain",
                )
                assertEquals(
                    5L,
                    countOn(isolated, "SELECT count(*) FROM budget_command_request"),
                    "the claim-first request rows must survive the chain",
                )
                assertEquals(
                    2L,
                    countOn(isolated, "SELECT count(*) FROM budget_config"),
                    "each chain keeps exactly one stable budget identity",
                )
                // The CATEGORY-scope chain exists with its stable category attribution; without the
                // catalog bootstrap and the seeded category the FK could not even be written.
                assertEquals(
                    2L,
                    countOn(isolated, "SELECT count(*) FROM budget_settings_history WHERE scope_kind = 'CATEGORY'"),
                    "the CATEGORY chain's two revisions must survive",
                )
                assertEquals(
                    2L,
                    countOn(isolated, "SELECT count(*) FROM budget_settings_history WHERE scope_category_id = 'cat-local'"),
                    "the CATEGORY chain must stay bound to the stable category id",
                )
                assertEquals(
                    1L,
                    countOn(isolated, "SELECT count(*) FROM budget_config WHERE scope_kind = 'CATEGORY' AND scope_category_id = 'cat-local'"),
                    "the CATEGORY config row must keep its FK target",
                )
                assertValueIdentical(source.path, snapshot.path, listOf("manual_expense_request")) { commonManualExpenseColumns }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFailedMigrationLeavesTheSnapshotUntouched() {
        val dir = tempDir()
        val source = File(dir, "source-v34-rollback.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V34)
            val snapshot = File(dir, "snapshot-v34-rollback")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            // Occupy the late sentinel so the migration aborts inside its outer transaction.
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}", foreignKeyProperties()).use { isolated ->
                isolated.execute(null, "CREATE TABLE manual_annotation_v35_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))", 0)
                assertIs<StrictMigrationResult.Failed>(migrateIsolatedSnapshotStrictlyOn(isolated, 34, setOf(34L)))
                assertEquals(34L, readAuthoritativeUserVersionOn(isolated))
            }
            // The failed migration left the snapshot's DATA untouched: the owner tables still
            // read value-for-value against the source library (the isolation guard's round-trip
            // side: a failed restore must never damage the source snapshot). The byte-level claim
            // is NOT asserted: the sentinel tamper above is a deliberate write to this copy.
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}").use { reopened ->
                assertEquals(34L, readAuthoritativeUserVersionOn(reopened))
                assertValueIdentical(
                    source.path,
                    snapshot.path,
                    migratedOwnerTables,
                )
                assertCatalogOwnersReadBackNonVacuously(reopened)
                assertValueIdentical(source.path, snapshot.path, manualRequestTables) { table ->
                    if (table == "manual_expense_request") commonManualExpenseColumns else null
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ seeding

    private enum class Stage {
        /** Fresh current schema. */
        V35,

        /** Current schema minus the four manual_*_request annotation columns. */
        V34,

        /** v34 minus the 11 08.A owner tables. */
        V33,
    }

    /**
     * Seeds one anonymous library at [stage]: a manual expense chain + its claim row and receipt
     * (with the annotation columns at v35 only), the tag catalog with its command receipt plus a
     * two-revision annotation history with its current pointer (at v34/v35 only), and — at EVERY
     * stage — the product catalog bootstrap plus two budget chains (a TOTAL chain of two limits and
     * a CLOSED revision, and a CATEGORY chain of two limits) through the real claim-first command
     * port (see [seedBudgetChain]); `32.sqm` created the budget tables on the v32 -> v33 edge.
     */
    private fun seedCurrentLibrary(
        url: String,
        stage: Stage,
    ) {
        JdbcSqliteDriver(url, foreignKeyProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            if (stage != Stage.V35) {
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                if (stage == Stage.V33) {
                    annotationOwnerTables.forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
                }
                manualRequestTables.forEach { table ->
                    driver.execute(null, "ALTER TABLE $table DROP COLUMN annotation_tag_ids", 0)
                    driver.execute(null, "ALTER TABLE $table DROP COLUMN annotation_merchant_id", 0)
                }
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            }
            driver.execute(null, "PRAGMA user_version = ${stageVersion(stage)}", 0)

            val database = LedgerDatabase(driver)
            // One manual expense chain.
            database.ledgerQueries.insertTransaction("tx-annotated", ledgerId, "EXPENSE")
            database.ledgerQueries.insertPostingSet("tx-annotated-posting-set-1", ledgerId)
            database.ledgerQueries.insertTransactionVersion(
                "tx-annotated-version-1",
                "tx-annotated",
                ledgerId,
                1L,
                "tx-annotated-posting-set-1",
                "2026-03-05T02:00:00Z",
                "2026-03-05T02:00:00Z",
                "2026-03-05T02:00:00Z",
                "lunch",
            )
            database.ledgerQueries.insertPosting("tx-annotated-posting-0", "tx-annotated-posting-set-1", ledgerId, 0L, "expense-account-local", 3_580L, "CNY", 2L)
            database.ledgerQueries.insertPosting("tx-annotated-posting-1", "tx-annotated-posting-set-1", ledgerId, 1L, "asset-payment-local", -3_580L, "CNY", 2L)
            database.ledgerQueries.insertTransactionCurrentVersion("tx-annotated", ledgerId, "tx-annotated-version-1")

            // The manual expense claim row: the annotation columns exist only at v35.
            if (stage == Stage.V35) {
                driver.execute(
                    null,
                    "INSERT INTO manual_expense_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker, annotation_tag_ids, annotation_merchant_id) " +
                        "VALUES ('$ledgerId', 'req-annotated', 3580, 'CNY', 2, 'cat-local', 'asset-payment-local', '2026-03-05T02:00:00Z', 'lunch', 'explicit_manual_save', 'tag-a', 'merchant-1')",
                    0,
                )
            } else {
                driver.execute(
                    null,
                    "INSERT INTO manual_expense_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker) " +
                        "VALUES ('$ledgerId', 'req-annotated', 3580, 'CNY', 2, 'cat-local', 'asset-payment-local', '2026-03-05T02:00:00Z', 'lunch', 'explicit_manual_save')",
                    0,
                )
            }
            database.ledgerQueries.insertConfirmedExpenseReceipt(ledgerId, "req-annotated", "confirmation-tx-annotated", "tx-annotated")

            if (stage != Stage.V33) {
                // Tag catalog + its command receipt (the 08.A owner receipt surface).
                database.ledgerQueries.insertCatalogItemVersion(ledgerId, 0L)
                database.ledgerQueries.advanceCatalogItemVersion(ledgerId)
                database.ledgerQueries.insertCatalogTag(ledger_id = ledgerId, tag_id = "tag-a", active = 1L, tombstoned = 0L, revision = 1L)
                database.ledgerQueries.insertCatalogMerchant(ledger_id = ledgerId, merchant_id = "merchant-1", active = 1L, tombstoned = 0L, revision = 1L)
                database.ledgerQueries.insertCatalogItemNameHistory(ledger_id = ledgerId, owner_kind = "tag", owner_id = "tag-a", version_number = 1L, name = "标签甲", status = "CURRENT")
                database.ledgerQueries.insertCatalogItemNameHistory(ledger_id = ledgerId, owner_kind = "merchant", owner_id = "merchant-1", version_number = 1L, name = "商家一", status = "CURRENT")
                // The receipt's FK targets the command REQUEST row: seed the claim first (the
                // claim-first protocol row the real chain writes).
                database.ledgerQueries.claimCatalogItemCommandRequest(
                    ledger_id = ledgerId,
                    request_id = "catalog-request-tag-a",
                    command = "create_item",
                    owner_kind = "tag",
                    owner_id = "tag-a",
                    request_snapshot = "{}",
                    input_fingerprint = "fp",
                    outcome = "ACCEPTED",
                    expected_revision = 0L,
                )
                database.ledgerQueries.insertCatalogItemCommandReceipt(
                    ledger_id = ledgerId,
                    request_id = "catalog-request-tag-a",
                    outcome = "ACCEPTED",
                    owner_kind = "tag",
                    owner_id = "tag-a",
                    new_revision = 1L,
                )

                // Multi-revision annotation history + current pointer (revision 1 kept, revision 2
                // cleared — the immutable history the restore must carry whole).
                database.ledgerQueries.insertTransactionAnnotationRevision(
                    ledger_id = ledgerId,
                    transaction_id = "tx-annotated",
                    annotation_revision = 1L,
                    observed_transaction_version_id = "tx-annotated-version-1",
                    merchant_id = "merchant-1",
                    request_id = "annot-request-1",
                    created_at = "2026-03-05T03:00:00Z",
                )
                database.ledgerQueries.insertTransactionAnnotationTag(ledgerId, "tx-annotated", 1L, "tag-a")
                database.ledgerQueries.insertTransactionAnnotationRevision(
                    ledger_id = ledgerId,
                    transaction_id = "tx-annotated",
                    annotation_revision = 2L,
                    observed_transaction_version_id = "tx-annotated-version-1",
                    merchant_id = null,
                    request_id = "annot-request-2",
                    created_at = "2026-03-06T03:00:00Z",
                )
                database.ledgerQueries.insertTransactionAnnotationCurrent(ledgerId, "tx-annotated", 2L)
                // The receipt's FK targets the annotation command REQUEST row (claim-first).
                database.ledgerQueries.claimTransactionAnnotationCommandRequest(
                    ledger_id = ledgerId,
                    request_id = "annot-request-2",
                    transaction_id = "tx-annotated",
                    request_snapshot = "{}",
                    input_fingerprint = "fp",
                    expected_annotation_revision = 1L,
                    expected_current_version_id = "tx-annotated-version-1",
                    outcome = "ACCEPTED",
                )
                database.ledgerQueries.insertTransactionAnnotationCommandReceipt(ledgerId, "annot-request-2", "ACCEPTED", "tx-annotated", 2L)
            }

            // Every leg carries a real budget chain: `32.sqm` is the v32 -> v33 edge, so a file
            // stamped 33 (Stage.V33) already contains the four budget tables, and neither `33.sqm`
            // nor `34.sqm` touches them. Writing the chain here (rather than only at V35) is what
            // makes the v33/v34 comparisons evidence of survival instead of empty-vs-empty.
            // The catalog comes FIRST because the CATEGORY chain's `budget_config` row carries a
            // real FK to `catalog_category`, and bootstrap itself fails closed on any existing
            // posting/manual reference the seed cannot resolve.
            assertIs<CatalogBootstrapResult.Seeded>(
                SqlDelightCatalogStore(database, driver).bootstrap(LedgerId(ledgerId), restoreCatalogSeed()),
            )
            seedCatalogCommandChain(database, driver)
            seedEntryPreferenceAndCounterpartyChain(database, driver)
            seedBudgetChain(database, driver)
        }
    }

    /**
     * Writes the P7-02 entry-preference and counterparty owners through their real product ports:
     *
     *  - [SqlDelightEntryPreferenceStore.togglePin] pins the seeded `asset-payment-local` account,
     *    which writes one `entry_pin` row. Only the `account` target kind is seeded; `category` is a
     *    legal kind per the table CHECK but this fixture does not exercise it.
     *  - [SqlDelightCounterpartyStore.create] writes one `counterparty` row and its version-1
     *    `counterparty_name_history` row, plus the hidden receivable `catalog_account` the port
     *    derives.
     *
     * All three tables come from `28.sqm` (v28 -> v29) and no later edge touches them, so they are
     * present on every stage; [seedCurrentLibrary] runs this on every leg, which is what makes the
     * comparison data rather than empty-vs-empty.
     */
    private fun seedEntryPreferenceAndCounterpartyChain(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
    ) {
        assertIs<EntryPinResult.Toggled>(
            SqlDelightEntryPreferenceStore(database).togglePin(
                target = EntryPinTarget.AccountTarget(LedgerId(ledgerId), AccountId("asset-payment-local")),
                pinnedAt = Instant.parse("2026-03-05T04:00:00Z"),
            ),
        )
        assertIs<CounterpartyCommandResult.Created>(
            SqlDelightCounterpartyStore(database, driver).create(
                ledgerId = LedgerId(ledgerId),
                counterpartyId = CounterpartyId("counterparty-roundtrip"),
                name = "Roundtrip Counterparty",
                receivableAccountId = AccountId("receivable-roundtrip"),
                currency = CurrencyUnit("CNY", 2),
            ),
        )
    }

    /**
     * Writes ONE real catalog management command through the product claim-first port
     * ([ExecuteCatalogCommand] over [SqlDelightCatalogStore]), so `catalog_command_request` and
     * `catalog_command_receipt` hold product-path rows instead of nothing.
     *
     * Bootstrap writes the catalog rows and `catalog_version`, but it never claims a command: after
     * the seed inserts it only runs the fail-closed `unresolvedReferences` scan and
     * `insertCatalogVersion`. No other part of this fixture
     * issued a catalog command either, so without this seed the two command tables would compare
     * empty-vs-empty — the vacuous "survival" claim the v33 budget leg below explicitly rejects.
     *
     * The command renames the seeded `asset-payment-local` account: a rename supersedes the current
     * name and appends a new `catalog_name_history` revision, so the seeded rows also exercise the
     * version/history owners rather than only the two command tables. A rename never mints entity
     * ids, so the constructor's entity-id source is required but never called here.
     */
    private fun seedCatalogCommandChain(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
    ) {
        val store = SqlDelightCatalogStore(database, driver)
        val command =
            ExecuteCatalogCommand(
                commitPort = store,
                requestIdSource = catalogRenameRequestId(),
                entityIdSource = unusedCatalogEntityIds(),
                categoryReferenceProbe = store,
            )
        // The bootstrap left the catalog at version 1; the rename is the first command to claim one.
        assertIs<CatalogCommandResult.Accepted>(
            command.renameAccount(
                ledgerId = LedgerId(ledgerId),
                accountId = AccountId("asset-payment-local"),
                newName = "本地支付改名",
                expectedCatalogVersion = 1L,
            ),
        )
    }

    private fun catalogRenameRequestId(): CatalogManagementRequestIdSource = CatalogManagementRequestIdSource { CatalogRequestId("catalog-request-account-rename") }

    /**
     * [ExecuteCatalogCommand] requires an entity-id source, but only the create paths call it; the
     * seeded rename mints nothing, so these ids are never persisted.
     */
    private fun unusedCatalogEntityIds(): CatalogEntityIdSource =
        CatalogEntityIdSource {
            CatalogEntityIds(
                manageableAccountId = AccountId("asset-managed-unused"),
                postingAccountId = AccountId("expense-account-unused"),
                parentCategoryId = CategoryId("expense-group-unused"),
                childCategoryId = CategoryId("expense-child-unused"),
            )
        }

    /**
     * The catalog the round-trip fixture needs. Bootstrap fails closed on any posting account or
     * manual-request category it does not know (`unresolvedReferences` scans `posting.account_id`
     * and `manual_expense_request.category_id`), so the three ids the seeded ledger rows already
     * name must exist: the `cat-local` EXPENSE leaf, its hidden `expense-account-local` posting
     * account, and the `asset-payment-local` funding account. `cat-local` is a level-2 leaf because
     * the product catalog validation refuses a level-1 category that carries a posting account.
     * Everything here is an anonymous synthetic value.
     */
    private fun restoreCatalogSeed(): CatalogSeed =
        CatalogSeed(
            accounts =
                listOf(
                    CatalogSeedAccount(
                        accountId = AccountId("expense-account-local"),
                        name = "本地支出",
                        kind = AccountKind.EXPENSE,
                        currency = CurrencyUnit("CNY", 2),
                        ownedByUser = false,
                        realAccount = false,
                        hidden = true,
                    ),
                    CatalogSeedAccount(
                        accountId = AccountId("asset-payment-local"),
                        name = "本地支付",
                        kind = AccountKind.ASSET,
                        currency = CurrencyUnit("CNY", 2),
                        ownedByUser = true,
                        realAccount = true,
                        hidden = false,
                    ),
                ),
            categories =
                listOf(
                    CatalogSeedCategory(
                        categoryId = CategoryId("cat-group-local"),
                        name = "本地支出",
                        kind = CategoryKind.EXPENSE,
                        parentId = null,
                        postingAccountId = null,
                    ),
                    CatalogSeedCategory(
                        categoryId = categoryLocal,
                        name = "本地餐饮",
                        kind = CategoryKind.EXPENSE,
                        parentId = CategoryId("cat-group-local"),
                        postingAccountId = AccountId("expense-account-local"),
                    ),
                ),
        )

    /**
     * Writes two budget chains through the REAL claim-first command chain ([SaveBudgetConfiguration]
     * over [SqlDelightBudgetStore]), so the request rows, the immutable settings history and the
     * receipts are all produced by the production guards rather than hand-inserted: a hand-written
     * row could not prove the roundtrip carries a legal chain.
     *
     *  - TOTAL `budget-roundtrip`: three revisions — two MONITORED limits then a CLOSED revision, so
     *    BOTH legal row shapes of `budget_settings_history` cross the snapshot.
     *  - CATEGORY `budget-roundtrip-category` on `cat-local`: two MONITORED revisions, an
     *    independent stable identity whose `budget_config` row carries the real `catalog_category`
     *    FK and whose scope must survive the round-trip value-for-value.
     *
     * Every command uses its own request id: the claim identity is `(ledgerId, requestId)`, so a
     * shared id would make the later command an identity conflict, not the next revision.
     */
    private fun seedBudgetChain(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
    ) {
        val catalogReader = SqlDelightCatalogStore(database, driver)

        fun command(
            requestId: String,
            mintedBudgetId: BudgetId,
        ): SaveBudgetConfiguration =
            SaveBudgetConfiguration(
                commitPort = SqlDelightBudgetStore(database, driver),
                requestIdSource = BudgetRequestIdSource { BudgetRequestId(requestId) },
                budgetIdSource = BudgetIdSource { mintedBudgetId },
                catalogReader = catalogReader,
                clock = LedgerClock { Instant.parse("2026-03-05T02:00:00Z") },
            )

        val march = YearMonth(2026, 3)
        assertIs<BudgetCommandResult.Accepted>(
            command("budget-request-1", budgetId)
                .setLimit(LedgerId(ledgerId), march, BudgetScope.Total, 100_000L, expectedRevision = 0L),
        )
        assertIs<BudgetCommandResult.Accepted>(
            command("budget-request-2", budgetId)
                .setLimit(LedgerId(ledgerId), march, BudgetScope.Total, 150_000L, expectedRevision = 1L),
        )
        // Close appends a CLOSED NULL-limit revision and keeps the whole history (spec section 3.3);
        // without it the restore evidence would only ever see the MONITORED row shape.
        assertIs<BudgetCommandResult.Accepted>(
            command("budget-request-3", budgetId)
                .close(LedgerId(ledgerId), march, BudgetScope.Total, expectedRevision = 2L),
        )
        // The independent CATEGORY chain (two MONITORED revisions, no CLOSED row).
        assertIs<BudgetCommandResult.Accepted>(
            command("budget-request-category-1", categoryBudgetId)
                .setLimit(LedgerId(ledgerId), march, BudgetScope.Category(categoryLocal), 80_000L, expectedRevision = 0L),
        )
        assertIs<BudgetCommandResult.Accepted>(
            command("budget-request-category-2", categoryBudgetId)
                .setLimit(LedgerId(ledgerId), march, BudgetScope.Category(categoryLocal), 90_000L, expectedRevision = 1L),
        )
    }

    private fun stageVersion(stage: Stage): Long =
        when (stage) {
            Stage.V35 -> 35L
            Stage.V34 -> 34L
            Stage.V33 -> 33L
        }

    // ------------------------------------------------------------------ equivalence probes

    /**
     * Reads BOTH seeded budget chains off a restored/migrated snapshot through the real store and
     * asserts the full immutable revision history FIELD BY FIELD. Revision 1's superseded limit,
     * the CLOSED revision's NULL limit and the independent CATEGORY chain are exactly the values a
     * "current config row only" read would lose.
     */
    private fun assertBothBudgetHistoriesReadBack(driver: SqlDriver) {
        val store = SqlDelightBudgetStore.forPlatformConfiguredDatabase(LedgerDatabase(driver))
        val settings = store.settingsHistory(LedgerId(ledgerId), budgetId)
        assertEquals(listOf(1L, 2L, 3L), settings.map { it.revisionNumber })
        assertEquals(listOf(false, false, true), settings.map { it.closed })
        assertEquals(listOf(100_000L, 150_000L, null), settings.map { it.limitMinorUnits })
        assertEquals(
            listOf("budget-request-1", "budget-request-2", "budget-request-3"),
            settings.map { it.requestId.value },
        )
        assertEquals(
            listOf("2026-03-05T02:00:00Z", "2026-03-05T02:00:00Z", "2026-03-05T02:00:00Z"),
            settings.map { it.createdAt },
        )
        val categorySettings = store.settingsHistory(LedgerId(ledgerId), categoryBudgetId)
        assertEquals(listOf(1L, 2L), categorySettings.map { it.revisionNumber })
        assertEquals(listOf(false, false), categorySettings.map { it.closed })
        assertEquals(listOf(80_000L, 90_000L), categorySettings.map { it.limitMinorUnits })
        assertEquals(
            listOf("budget-request-category-1", "budget-request-category-2"),
            categorySettings.map { it.requestId.value },
        )
        assertEquals(
            listOf("2026-03-05T02:00:00Z", "2026-03-05T02:00:00Z"),
            categorySettings.map { it.createdAt },
        )
    }

    /**
     * Non-vacuity guard for the restored copy's seeded owner rows: every [catalogOwnerTables] and
     * [preferenceAndCounterpartyTables] entry plus `confirmed_expense_receipt` must hold at least one
     * row, so `assertValueIdentical` compares data rather than two empty results. Without this, a
     * table the fixture never writes (for example the command tables, which bootstrap alone does not
     * populate) would "survive" the round-trip vacuously — the same trap
     * [aV33SnapshotMigratesThroughTheFullChainWithZeroBackfillAndSurvivingRows] rejects for the
     * budget tables.
     *
     * The expected `catalog_name_history` total is 6: four bootstrap seed revisions (two accounts,
     * two categories), one appended by the catalog rename, and one for the hidden receivable account
     * the counterparty port derives.
     */
    private fun assertCatalogOwnersReadBackNonVacuously(driver: SqlDriver) {
        catalogOwnerTables.forEach { table ->
            assertTrue(
                countOn(driver, "SELECT count(*) FROM $table") > 0L,
                "$table must hold seeded rows, otherwise its round-trip comparison is vacuous",
            )
        }
        assertEquals(
            1L,
            countOn(driver, "SELECT count(*) FROM catalog_command_request"),
            "the seeded catalog command claims exactly one request row",
        )
        assertEquals(
            1L,
            countOn(driver, "SELECT count(*) FROM catalog_command_receipt"),
            "the seeded catalog command writes exactly one receipt",
        )
        // Four bootstrap seed revisions (two accounts, two categories), one appended by the
        // catalog rename, and one for the hidden receivable account the counterparty port derives.
        assertEquals(
            6L,
            countOn(driver, "SELECT count(*) FROM catalog_name_history"),
            "every seeded catalog and counterparty owner must keep its name-history revision",
        )
        // The receipt is compared on every leg too, so guard it against empty-vs-empty as well.
        assertEquals(
            1L,
            countOn(driver, "SELECT count(*) FROM confirmed_expense_receipt"),
            "the manual expense confirmation receipt must survive every leg",
        )
        // Bootstrap stamps version 1; the accepted rename advances it by one.
        assertEquals(
            1L,
            countOn(driver, "SELECT count(*) FROM catalog_version WHERE version = 2"),
            "the accepted catalog command must advance the catalog version to 2",
        )
        // The P7-02 preference/counterparty owners are compared on every leg, so guard them too.
        preferenceAndCounterpartyTables.forEach { table ->
            assertTrue(
                countOn(driver, "SELECT count(*) FROM $table") > 0L,
                "$table must hold seeded rows, otherwise its round-trip comparison is vacuous",
            )
        }
        assertEquals(
            1L,
            countOn(driver, "SELECT count(*) FROM counterparty_name_history"),
            "the counterparty create writes exactly one version-1 name-history row",
        )
    }

    /**
     * Reads EVERY row of [table] on both files and compares them as values ([projection] defaults to
     * all columns). Row order comes from [deterministicOrder] — per table, because the four budget
     * tables need a key-based order rather than `rowid`.
     */
    private fun assertValueIdentical(
        sourcePath: String,
        snapshotPath: String,
        tables: List<String>,
        projection: (String) -> String? = { null },
    ) {
        tables.forEach { table ->
            val columns = projection(table) ?: "*"
            assertEquals(
                rowsOf(sourcePath, table, columns, deterministicOrder(table)),
                rowsOf(snapshotPath, table, columns, deterministicOrder(table)),
                "$table round-trip drift",
            )
        }
    }

    /**
     * Deterministic row order per table.
     *
     * `rowid` is NOT a contract for row order: `VACUUM` may renumber implicit rowids, so an
     * `ORDER BY rowid` comparison is not guaranteed by the format. It is stable in practice here
     * only because `VACUUM` copies rows in rowid order, which is why the snapshot this class takes
     * with `VACUUM INTO` currently reads back in the same order.
     *
     * The 11 08.A annotation tables and the manual/receipt tables therefore KEEP `ORDER BY rowid`
     * deliberately: their value assertions were already proven under that order and this batch must
     * not perturb them.
     *
     * The four budget tables instead order by their declared TEXT COMPOSITE primary key, because
     * that key IS a contract: it fully determines the order of these rows, unlike an implicit
     * rowid. They are new comparisons with no prior evidence to preserve, so the stronger ordering
     * costs nothing. Ordering by the full primary key is stable across VACUUM and across the
     * copy/migrate path.
     */
    private fun deterministicOrder(table: String): String =
        when (table) {
            "budget_config" -> "ledger_id, budget_id"
            "budget_command_request" -> "ledger_id, request_id"
            "budget_settings_history" -> "ledger_id, budget_id, revision_number"
            "budget_command_receipt" -> "ledger_id, request_id"
            // The catalog and posting-set owners have declared primary keys, which — like the
            // budget keys above — fully determine row order, unlike an implicit rowid that
            // VACUUM may renumber.
            "catalog_version" -> "ledger_id"
            "catalog_account" -> "ledger_id, account_id"
            "catalog_category" -> "ledger_id, category_id"
            "catalog_name_history" -> "ledger_id, owner_kind, owner_id, version_number"
            "catalog_command_request" -> "ledger_id, request_id"
            "catalog_command_receipt" -> "ledger_id, request_id"
            "posting_set" -> "posting_set_id"
            "entry_pin" -> "ledger_id, target_kind, target_id"
            "counterparty" -> "ledger_id, counterparty_id"
            "counterparty_name_history" -> "ledger_id, counterparty_id, version_number"
            else -> "rowid"
        }

    /**
     * All rows of [table] ([order] clause), the projected columns as stored values; NULL stays NULL.
     * The JDBC access rides the same `JdbcSqliteDriver` the rest of the module uses (a bare
     * `DriverManager.getConnection` has no registered sqlite driver in this JVM).
     */
    private fun rowsOf(
        path: String,
        table: String,
        columns: String,
        order: String,
    ): List<List<String?>> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return try {
            driver.getConnection().createStatement().use { statement ->
                statement.executeQuery("SELECT $columns FROM $table ORDER BY $order").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add((1..rows.metaData.columnCount).map { column -> rows.getString(column) })
                        }
                    }
                }
            }
        } finally {
            driver.close()
        }
    }

    private fun countOn(
        driver: SqlDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    private fun columnInfo(
        driver: SqlDriver,
        table: String,
    ): List<String> =
        driver
            .executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    val rows =
                        buildList {
                            while (cursor.next().value) {
                                add("${cursor.getString(1)}|${cursor.getString(2)}|${cursor.getLong(3)}|${cursor.getLong(5)}")
                            }
                        }
                    app.cash.sqldelight.db.QueryResult
                        .Value(rows)
                },
                0,
            ).value

    private fun tempDir(): File {
        val directory = Files.createTempDirectory("p708-restore-roundtrip").toFile()
        directory.deleteOnExit()
        return directory
    }

    private fun foreignKeyProperties(): java.util.Properties = java.util.Properties().apply { setProperty("foreign_keys", "true") }
}
