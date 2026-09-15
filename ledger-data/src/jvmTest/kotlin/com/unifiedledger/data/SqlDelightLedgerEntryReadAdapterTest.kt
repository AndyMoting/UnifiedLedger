package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualCreationChain
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-03.A read-model adapter evidence (D-145, spec sections 4.1/5, Appendix A) on an
 * anonymous temp-file database: effective kind via `COALESCE(canonical_kind, kind)`,
 * both persisted times plus the current note, creation-entry reverse lineage for the
 * import and manual chains, the read-only reconciliation leg projection with
 * eligibility per spec section 3.2.1, ledger isolation and read-only invariants.
 * Schema/migration zero-change stays proven by the migration verifier task.
 */
class SqlDelightLedgerEntryReadAdapterTest {
    private val ledgerId = LedgerId("ledger-p703-entry-test")
    private val otherLedgerId = LedgerId("ledger-p703-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetAccountId = AccountId("asset-account-p703")
    private val receivableAccountId = AccountId("receivable-account-p703")
    private val expenseAccountId = AccountId("expense-account-p703")
    private val incomeAccountId = AccountId("income-account-p703")

    @Test
    fun ledgerEntryRowsProjectCanonicalKindOverLegacyKindWithTimesAndNote() {
        val path = newDatabaseFile("p7-03-entry-kinds-")
        try {
            val harness = EntryReadHarness(path)
            // New kinds are persisted with the legacy-compatible kind column and the real
            // kind in canonical_kind (insertTransaction compatibility rule). Postings are
            // inserted out of index order to prove stable posting_index ordering.
            harness.insertTransaction(
                transactionId = TransactionId("tx-lend"),
                kind = TransactionKind.LEND,
                versions =
                    listOf(
                        EntryVersion(
                            versionId = TransactionVersionId("version-lend-1"),
                            occurredAt = "2026-03-01T02:00:00Z",
                            statisticsAt = "2026-03-05T02:00:00Z",
                            note = "lend note",
                        ),
                    ),
                postings =
                    listOf(
                        EntryPosting(PostingId("posting-lend-in"), 1, receivableAccountId, 10_000L),
                        EntryPosting(PostingId("posting-lend-out"), 0, assetAccountId, -10_000L),
                    ),
            )
            // Legacy rows keep canonical_kind NULL and must still read back their own kind.
            harness.insertTransaction(
                transactionId = TransactionId("tx-legacy-expense"),
                kind = TransactionKind.EXPENSE,
                versions =
                    listOf(
                        EntryVersion(
                            versionId = TransactionVersionId("version-expense-1"),
                            occurredAt = "2026-03-02T02:00:00Z",
                            statisticsAt = "2026-03-02T02:00:00Z",
                            note = null,
                        ),
                    ),
                postings =
                    listOf(
                        EntryPosting(PostingId("posting-expense"), 0, expenseAccountId, 3_000L),
                        EntryPosting(PostingId("posting-payment"), 1, assetAccountId, -3_000L),
                    ),
            )
            harness.insertTransaction(
                transactionId = TransactionId("tx-collect"),
                kind = TransactionKind.COLLECT,
                versions =
                    listOf(
                        EntryVersion(
                            versionId = TransactionVersionId("version-collect-1"),
                            occurredAt = "2026-03-03T02:00:00Z",
                            statisticsAt = "2026-03-04T02:00:00Z",
                            note = "collect note",
                        ),
                    ),
                postings =
                    listOf(
                        EntryPosting(PostingId("posting-collect-principal-in"), 0, assetAccountId, 4_000L),
                        EntryPosting(PostingId("posting-collect-principal-out"), 1, receivableAccountId, -4_000L),
                        EntryPosting(PostingId("posting-collect-interest"), 2, incomeAccountId, -500L),
                    ),
            )

            val rows = harness.adapter.loadLedgerEntryRows(ledgerId).associateBy { it.transactionId }
            assertEquals(setOf("tx-lend", "tx-legacy-expense", "tx-collect"), rows.keys.map { it.value }.toSet())
            assertEquals(TransactionKind.LEND, rows.getValue(TransactionId("tx-lend")).kind)
            assertEquals(TransactionKind.EXPENSE, rows.getValue(TransactionId("tx-legacy-expense")).kind)
            assertEquals(TransactionKind.COLLECT, rows.getValue(TransactionId("tx-collect")).kind)

            val lendRow = rows.getValue(TransactionId("tx-lend"))
            assertEquals(TransactionVersionId("version-lend-1"), lendRow.currentVersionId)
            assertEquals(Instant.parse("2026-03-01T02:00:00Z"), lendRow.occurredAt)
            assertEquals(Instant.parse("2026-03-05T02:00:00Z"), lendRow.statisticsAt)
            assertEquals("lend note", lendRow.note)
            assertEquals(listOf("posting-lend-out", "posting-lend-in"), lendRow.postings.map { it.id.value })

            val collectRow = rows.getValue(TransactionId("tx-collect"))
            assertEquals(
                listOf("posting-collect-principal-in", "posting-collect-principal-out", "posting-collect-interest"),
                collectRow.postings.map { it.id.value },
            )
            assertNull(rows.getValue(TransactionId("tx-legacy-expense")).note)
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun ledgerEntryRowsAreCurrentVersionOnlyLedgerScopedAndReadOnly() {
        val path = newDatabaseFile("p7-03-entry-current-")
        try {
            val harness = EntryReadHarness(path)
            harness.insertTransaction(
                transactionId = TransactionId("tx-noted"),
                kind = TransactionKind.EXPENSE,
                versions =
                    listOf(
                        EntryVersion(
                            versionId = TransactionVersionId("version-noted-1"),
                            occurredAt = "2026-02-10T02:00:00Z",
                            statisticsAt = "2026-02-10T02:00:00Z",
                            note = "first note",
                        ),
                    ),
                postings =
                    listOf(
                        EntryPosting(PostingId("posting-noted-expense"), 0, expenseAccountId, 3_000L),
                        EntryPosting(PostingId("posting-noted-payment"), 1, assetAccountId, -3_000L),
                    ),
            )
            // A note correction appends a version and moves the current pointer; only the
            // pointed version (with its own note and times) may be projected.
            harness.database.ledgerQueries.copyCurrentVersionWithNewNote(
                version_id = "version-noted-2",
                transaction_id = "tx-noted",
                ledger_id = ledgerId.value,
                expected_current_version_id = "version-noted-1",
                note = "replacement note",
            )
            harness.database.ledgerQueries.updateCurrentVersion(
                transaction_id = "tx-noted",
                current_version_id = "version-noted-2",
            )

            val transactionsBefore = harness.countTransactions()
            val rows = harness.adapter.loadLedgerEntryRows(ledgerId)
            assertEquals(1, rows.size)
            assertEquals(TransactionVersionId("version-noted-2"), rows.single().currentVersionId)
            assertEquals("replacement note", rows.single().note)
            assertEquals(transactionsBefore, harness.countTransactions())

            assertTrue(harness.adapter.loadLedgerEntryRows(otherLedgerId).isEmpty())
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /**
     * C01 statistics-time correction vector (spec section 7 C01, R-Q06-1/R-Q06-2): a correction
     * that appends a newer version whose `statistics_at` differs must land the transaction in
     * the new month via the version pointer — the read model projects only the pointed version,
     * exactly once, with the new statistics time; no second current row keeps the old time and
     * the superseded version is never double-counted as an extra row.
     */
    @Test
    fun statisticsAtCorrectionAppendsAVersionAndOnlyTheNewTimeIsProjected() {
        val path = newDatabaseFile("p7-03-entry-statistics-correction-")
        try {
            val harness = EntryReadHarness(path)
            harness.insertTransaction(
                transactionId = TransactionId("tx-corrected-time"),
                kind = TransactionKind.EXPENSE,
                versions =
                    listOf(
                        EntryVersion(
                            // 2026-03-05T02:00:00Z = March 5 in Asia/Shanghai (the old month).
                            versionId = TransactionVersionId("version-corrected-1"),
                            occurredAt = "2026-03-05T02:00:00Z",
                            statisticsAt = "2026-03-05T02:00:00Z",
                            note = "pre-correction note",
                        ),
                    ),
                postings =
                    listOf(
                        EntryPosting(PostingId("posting-corrected-expense"), 0, expenseAccountId, 3_000L),
                        EntryPosting(PostingId("posting-corrected-payment"), 1, assetAccountId, -3_000L),
                    ),
            )
            val transactionsBefore = harness.countTransactions()
            // The correction appends a newer version (the same append-then-move-pointer seam the
            // note-only correction at copyCurrentVersionWithNewNote uses) whose statistics_at moved
            // to 2026-04-05T02:00:00Z = April 5 in Asia/Shanghai, while occurred_at/effective_at and
            // the posting set stay shared with the superseded version.
            harness.database.ledgerQueries.insertTransactionVersion(
                "version-corrected-2",
                "tx-corrected-time",
                ledgerId.value,
                2L,
                "posting-set-version-corrected-1",
                "2026-03-05T02:00:00Z",
                "2026-04-05T02:00:00Z",
                "2026-03-05T02:00:00Z",
                "corrected statistics month",
            )
            harness.database.ledgerQueries.updateCurrentVersion(
                transaction_id = "tx-corrected-time",
                current_version_id = "version-corrected-2",
            )

            val rows = harness.adapter.loadLedgerEntryRows(ledgerId)
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(TransactionId("tx-corrected-time"), row.transactionId)
            assertEquals(TransactionVersionId("version-corrected-2"), row.currentVersionId)
            assertEquals(Instant.parse("2026-04-05T02:00:00Z"), row.statisticsAt)
            assertEquals(Instant.parse("2026-03-05T02:00:00Z"), row.occurredAt)
            assertEquals("corrected statistics month", row.note)
            assertEquals(
                listOf("posting-corrected-expense", "posting-corrected-payment"),
                row.postings.map { it.id.value },
            )
            assertEquals(transactionsBefore, harness.countTransactions())
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun importCreationConfirmationReverseLookupResolvesOnlyImportedTransactions() {
        val path = newDatabaseFile("p7-03-entry-import-")
        try {
            val harness = EntryReadHarness(path)
            harness.insertTransaction(
                transactionId = TransactionId("tx-imported"),
                kind = TransactionKind.EXPENSE,
                versions = listOf(defaultVersion(TransactionVersionId("version-imported-1"))),
                postings = emptyList(),
            )
            harness.insertTransaction(
                transactionId = TransactionId("tx-manual"),
                kind = TransactionKind.EXPENSE,
                versions = listOf(defaultVersion(TransactionVersionId("version-manual-1"))),
                postings = emptyList(),
            )
            harness.insertImportCreationChain(TransactionId("tx-imported"))
            harness.insertManualExpenseReceipt("tx-manual")

            val imported = harness.adapter.findImportCreationConfirmation(ledgerId, TransactionId("tx-imported"))
            assertEquals("confirmation-p703-import", imported?.confirmationId)
            assertEquals("creation", imported?.operationClass)
            assertEquals("request-p703-import", imported?.requestId)

            assertNull(harness.adapter.findImportCreationConfirmation(ledgerId, TransactionId("tx-manual")))
            assertNull(harness.adapter.findImportCreationConfirmation(otherLedgerId, TransactionId("tx-imported")))
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun manualCreationReceiptReverseLookupResolvesAllFourChains() {
        val path = newDatabaseFile("p7-03-entry-manual-")
        try {
            val harness = EntryReadHarness(path)
            harness.insertMinimalTransaction(TransactionId("tx-expense"), TransactionKind.EXPENSE)
            harness.insertMinimalTransaction(TransactionId("tx-income"), TransactionKind.INCOME)
            harness.insertMinimalTransaction(TransactionId("tx-transfer"), TransactionKind.ACCOUNT_TRANSFER)
            harness.insertMinimalTransaction(TransactionId("tx-lending"), TransactionKind.LEND)
            harness.insertManualExpenseReceipt("tx-expense")
            harness.insertManualIncomeReceipt("tx-income")
            harness.insertManualTransferReceipt("tx-transfer")
            harness.insertManualLendingReceipt("tx-lending")

            val expense = harness.adapter.findManualCreationReceipt(ledgerId, TransactionId("tx-expense"))
            assertEquals(ManualCreationChain.EXPENSE, expense?.chain)
            assertEquals("confirmation-p703-expense", expense?.confirmationId)
            assertEquals(ManualCreationChain.INCOME, harness.adapter.findManualCreationReceipt(ledgerId, TransactionId("tx-income"))?.chain)
            assertEquals(ManualCreationChain.TRANSFER, harness.adapter.findManualCreationReceipt(ledgerId, TransactionId("tx-transfer"))?.chain)
            assertEquals(ManualCreationChain.LENDING, harness.adapter.findManualCreationReceipt(ledgerId, TransactionId("tx-lending"))?.chain)

            assertNull(harness.adapter.findManualCreationReceipt(ledgerId, TransactionId("tx-unknown")))
            assertNull(harness.adapter.findManualCreationReceipt(otherLedgerId, TransactionId("tx-expense")))
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun transactionReconciliationLegsProjectStatusEligibilityAndEvidenceReadonly() {
        val path = newDatabaseFile("p7-03-entry-legs-")
        try {
            val harness = EntryReadHarness(path)
            harness.insertEvidenceScaffold()
            // Imported transfer with rg03 posting semantics: principal legs eligible, fee
            // leg explicitly ineligible (reconciliation_eligible = 0 takes precedence over
            // an active evidence link, spec section 3.2.1).
            harness.insertTransaction(
                transactionId = TransactionId("tx-transfer-rg03"),
                kind = TransactionKind.ACCOUNT_TRANSFER,
                versions = listOf(defaultVersion(TransactionVersionId("version-transfer-1"))),
                postings =
                    listOf(
                        EntryPosting(PostingId("posting-transfer-source"), 0, assetAccountId, -6_000L),
                        EntryPosting(PostingId("posting-transfer-fee"), 1, expenseAccountId, 100L),
                        EntryPosting(PostingId("posting-transfer-destination"), 2, receivableAccountId, 5_900L),
                    ),
            )
            harness.insertRg03Semantics("posting-transfer-source", role = "TRANSFER_PRINCIPAL_OUT", eligible = 1L)
            harness.insertRg03Semantics("posting-transfer-fee", role = "TRANSFER_FEE", eligible = 0L, categoryId = "category-p703-fee")
            harness.insertRg03Semantics("posting-transfer-destination", role = "TRANSFER_PRINCIPAL_IN", eligible = 1L)
            harness.insertPostingReconciliation("posting-transfer-source", reconciliationId = "reconciliation-p703-1", status = "PARTIAL")
            harness.insertActiveEvidenceLink("posting-transfer-fee", linkId = "link-p703-fee", transactionId = "tx-transfer-rg03")

            // Manual transfer without rg03 rows: eligibility only via existing evidence or
            // reconciliation rows (spec section 3.2.1(b)); plain manual legs stay ineligible.
            harness.insertTransaction(
                transactionId = TransactionId("tx-transfer-manual"),
                kind = TransactionKind.ACCOUNT_TRANSFER,
                versions = listOf(defaultVersion(TransactionVersionId("version-transfer-m1"))),
                postings =
                    listOf(
                        EntryPosting(PostingId("posting-manual-source"), 0, assetAccountId, -1_000L),
                        EntryPosting(PostingId("posting-manual-destination"), 1, receivableAccountId, 1_000L),
                    ),
            )
            harness.insertActiveEvidenceLink("posting-manual-source", linkId = "link-p703-manual", transactionId = "tx-transfer-manual")

            val transactionsBefore = harness.countTransactions()
            val postingsBefore = harness.countPostings()

            val rg03Legs =
                harness.adapter
                    .loadTransactionReconciliationLegs(ledgerId, TransactionId("tx-transfer-rg03"))
                    .associateBy { it.postingId }
            assertEquals(3, rg03Legs.size)
            val sourceLeg = rg03Legs.getValue(PostingId("posting-transfer-source"))
            assertEquals(true, sourceLeg.rg03ReconciliationEligible)
            assertEquals(true, sourceLeg.hasReconciliationRow)
            assertEquals(false, sourceLeg.hasActiveEvidenceLink)
            assertEquals("PARTIAL", sourceLeg.statusStorageValue)
            val feeLeg = rg03Legs.getValue(PostingId("posting-transfer-fee"))
            assertEquals(false, feeLeg.rg03ReconciliationEligible)
            assertEquals(true, feeLeg.hasActiveEvidenceLink)
            val destinationLeg = rg03Legs.getValue(PostingId("posting-transfer-destination"))
            assertEquals(true, destinationLeg.rg03ReconciliationEligible)
            assertEquals("PENDING", destinationLeg.statusStorageValue)

            val manualLegs =
                harness.adapter
                    .loadTransactionReconciliationLegs(ledgerId, TransactionId("tx-transfer-manual"))
                    .associateBy { it.postingId }
            val manualSource = manualLegs.getValue(PostingId("posting-manual-source"))
            assertNull(manualSource.rg03ReconciliationEligible)
            assertEquals(true, manualSource.hasActiveEvidenceLink)
            assertEquals("PENDING", manualSource.statusStorageValue)
            val manualDestination = manualLegs.getValue(PostingId("posting-manual-destination"))
            assertNull(manualDestination.rg03ReconciliationEligible)
            assertEquals(false, manualDestination.hasActiveEvidenceLink)
            assertEquals(false, manualDestination.hasReconciliationRow)

            assertEquals(0, harness.adapter.loadTransactionReconciliationLegs(otherLedgerId, TransactionId("tx-transfer-rg03")).size)
            assertEquals(0, harness.adapter.loadTransactionReconciliationLegs(ledgerId, TransactionId("tx-unknown")).size)
            assertEquals(transactionsBefore, harness.countTransactions())
            assertEquals(postingsBefore, harness.countPostings())
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun newDatabaseFile(prefix: String): Path = Files.createTempFile(prefix, ".db")

    private fun defaultVersion(versionId: TransactionVersionId) =
        EntryVersion(
            versionId = versionId,
            occurredAt = "2026-03-10T02:00:00Z",
            statisticsAt = "2026-03-10T02:00:00Z",
            note = null,
        )
}

private class EntryPosting(
    val postingId: PostingId,
    val postingIndex: Long,
    val accountId: AccountId,
    val amountMinor: Long,
)

private class EntryVersion(
    val versionId: TransactionVersionId,
    val occurredAt: String,
    val statisticsAt: String,
    val note: String?,
)

/** Synthetic anonymous fixture harness; every id and amount is test-local. */
private class EntryReadHarness(
    path: Path,
) {
    private val ledgerId = LedgerId("ledger-p703-entry-test")
    private val cny = CurrencyUnit("CNY", 2)
    val url = "jdbc:sqlite:${path.absolutePathString()}"
    private val driver = JdbcSqliteDriver(url)
    val database: LedgerDatabase
    val adapter: LedgerCurrentStateReadPort

    init {
        LedgerDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        database = LedgerDatabase(driver)
        adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
    }

    fun close() = driver.close()

    fun countTransactions(): Long = database.ledgerQueries.countTransactions().executeAsOne()

    fun countPostings(): Long = database.ledgerQueries.countPostings().executeAsOne()

    fun insertMinimalTransaction(
        transactionId: TransactionId,
        kind: TransactionKind,
    ) {
        // canonical_kind is derived by the query's CASE expression from :kind.
        database.ledgerQueries.insertTransaction(transactionId.value, ledgerId.value, kind.name)
    }

    fun insertTransaction(
        transactionId: TransactionId,
        kind: TransactionKind,
        versions: List<EntryVersion>,
        postings: List<EntryPosting>,
    ) {
        database.ledgerQueries.insertTransaction(transactionId.value, ledgerId.value, kind.name)
        versions.forEachIndexed { index, version ->
            val postingSetId = PostingSetId("posting-set-${version.versionId.value}")
            database.ledgerQueries.insertPostingSet(postingSetId.value, ledgerId.value)
            database.ledgerQueries.insertTransactionVersion(
                version.versionId.value,
                transactionId.value,
                ledgerId.value,
                (index + 1).toLong(),
                postingSetId.value,
                version.occurredAt,
                version.statisticsAt,
                version.occurredAt,
                version.note,
            )
            postings.forEach { posting ->
                database.ledgerQueries.insertPosting(
                    posting.postingId.value,
                    postingSetId.value,
                    ledgerId.value,
                    posting.postingIndex,
                    posting.accountId.value,
                    posting.amountMinor,
                    cny.code,
                    cny.precision.toLong(),
                )
            }
        }
        database.ledgerQueries.insertTransactionCurrentVersion(
            transactionId.value,
            ledgerId.value,
            versions.last().versionId.value,
        )
    }

    /** Minimal import spine so evidence links and confirmations satisfy their foreign keys. */
    fun insertEvidenceScaffold() {
        database.ledgerQueries.claimImportRequest(ledgerId.value, "request-p703-import", "confirm_candidate")
        database.ledgerQueries.insertImportSourceRecord(
            ledgerId.value,
            "source-p703-import",
            "request-p703-import",
            "input-ref-p703",
            0L,
            "ordinary_flow_source",
            "content-hash-p703",
            1L,
            "valid_incomplete",
            null,
            null,
            null,
            null,
            null,
            null,
            "UNRESOLVED",
            "rule-p703",
            1L,
            "2026-01-01T00:00:00Z",
        )
        database.ledgerQueries.insertImportEvidence(
            ledgerId.value,
            "evidence-p703-import",
            "source-p703-import",
            "source_observation",
            "2026-01-01T00:00:00Z",
        )
    }

    fun insertImportCreationChain(transactionId: TransactionId) {
        insertEvidenceScaffold()
        database.ledgerQueries.insertImportCandidate(
            ledgerId.value,
            "candidate-p703-import",
            "source-p703-import",
            "ordinary_flow",
            "exact",
            "rule-p703",
            1L,
        )
        // The spine trigger requires pending_confirmation before confirmed (state machine).
        database.ledgerQueries.insertImportStatusHistory(
            ledgerId.value,
            "candidate-p703-import",
            1L,
            "status-p703-pending",
            "pending_confirmation",
            "request-p703-import",
            "creation",
        )
        database.ledgerQueries.insertImportStatusHistory(
            ledgerId.value,
            "candidate-p703-import",
            2L,
            "status-p703-import",
            "confirmed",
            "request-p703-import",
            "creation",
        )
        database.ledgerQueries.insertImportConfirmation(
            ledgerId.value,
            "confirmation-p703-import",
            "request-p703-import",
            "candidate-p703-import",
            "status-p703-import",
            transactionId.value,
            "creation",
            "2026-03-10T02:00:00Z",
        )
    }

    fun insertManualExpenseReceipt(receiptTransactionId: String) {
        database.ledgerQueries.claimManualExpenseRequest(
            ledgerId.value,
            "request-p703-expense",
            3_580L,
            cny.code,
            cny.precision.toLong(),
            "category-p703",
            "asset-account-p703",
            "2026-01-15T00:30:00Z",
            "",
            "explicit_manual_save",
        )
        database.ledgerQueries.insertConfirmedExpenseReceipt(
            ledgerId.value,
            "request-p703-expense",
            "confirmation-p703-expense",
            receiptTransactionId,
        )
    }

    fun insertManualIncomeReceipt(receiptTransactionId: String) {
        database.ledgerQueries.claimManualIncomeRequest(
            ledgerId.value,
            "request-p703-income",
            5_000L,
            cny.code,
            cny.precision.toLong(),
            "category-p703",
            "income-account-p703",
            "2026-01-15T00:30:00Z",
            "",
            "explicit_manual_save",
        )
        database.ledgerQueries.insertConfirmedIncomeReceipt(
            ledgerId.value,
            "request-p703-income",
            "confirmation-p703-income",
            receiptTransactionId,
        )
    }

    fun insertManualTransferReceipt(receiptTransactionId: String) {
        database.ledgerQueries.claimManualTransferRequest(
            ledgerId.value,
            "request-p703-transfer",
            "asset-account-p703",
            "receivable-account-p703",
            6_000L,
            cny.code,
            cny.precision.toLong(),
            100L,
            "expense-account-p703",
            "2026-01-15T00:30:00Z",
            "",
            "explicit_manual_save",
        )
        database.ledgerQueries.insertConfirmedTransferReceipt(
            ledgerId.value,
            "request-p703-transfer",
            "confirmation-p703-transfer",
            receiptTransactionId,
        )
    }

    fun insertManualLendingReceipt(receiptTransactionId: String) {
        database.ledgerQueries.claimManualLendingRequest(
            ledgerId.value,
            "request-p703-lending",
            "LEND",
            "counterparty-p703",
            "asset-account-p703",
            10_000L,
            0L,
            0L,
            null,
            null,
            cny.code,
            cny.precision.toLong(),
            "2026-01-15T00:30:00Z",
            "",
            "explicit_manual_save",
        )
        database.ledgerQueries.insertConfirmedLendingReceipt(
            ledgerId.value,
            "request-p703-lending",
            "confirmation-p703-lending",
            receiptTransactionId,
        )
    }

    fun insertRg03Semantics(
        postingId: String,
        role: String,
        eligible: Long,
        categoryId: String? = null,
    ) {
        database.ledgerQueries.insertRg03TransferPostingSemantic(ledgerId.value, postingId, role, categoryId, eligible)
    }

    fun insertPostingReconciliation(
        postingId: String,
        reconciliationId: String,
        status: String,
    ) {
        // The current-projection guard requires a history row at the new latest_sequence
        // with the new status, mirroring the P4-08 store's update path: history starts at
        // sequence 1 (initial PENDING) and advances one sequence per status change.
        database.ledgerQueries.claimP408ReconciliationRequest(ledgerId.value, "request-p703-p408", "confirm_link", "fingerprint-p703")
        database.ledgerQueries.insertP408PostingReconciliation(ledgerId.value, reconciliationId, postingId)
        database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledgerId.value,
            reconciliationId,
            1L,
            "PENDING",
            null,
            "request-p703-p408",
            "2026-03-11T00:00:00Z",
        )
        database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledgerId.value,
            reconciliationId,
            2L,
            status,
            null,
            "request-p703-p408",
            "2026-03-11T00:00:00Z",
        )
        database.ledgerQueries.updateP408PostingReconciliation(
            status = status,
            latest_sequence = 2L,
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
        )
    }

    fun insertActiveEvidenceLink(
        postingId: String,
        linkId: String,
        transactionId: String,
    ) {
        database.ledgerQueries.claimP408ReconciliationRequest(ledgerId.value, "request-p703-p408", "confirm_link", "fingerprint-p703")
        database.ledgerQueries.insertP408EvidenceLink(
            ledgerId.value,
            linkId,
            "evidence-p703-import",
            postingId,
            transactionId,
            "real_account_posting",
            1L,
            "amount,currency,direction,account,occurred_at_window",
            "candidate-p703-import",
            "request-p703-p408",
            "2026-03-11T00:00:00Z",
        )
        database.ledgerQueries.insertP408EvidenceLinkHistory(
            ledgerId.value,
            linkId,
            1L,
            "active",
            "confirmed",
            "request-p703-p408",
            "2026-03-11T00:00:00Z",
        )
    }
}
