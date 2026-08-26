package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCandidateRejectRequest
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportResolvedSourceFacts
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ImportStatusIdSource
import com.unifiedledger.application.RejectImportCandidate
import com.unifiedledger.application.import.wechat.WechatBatchOutcome
import com.unifiedledger.application.import.wechat.WechatBillParser
import com.unifiedledger.application.import.wechat.WechatRowResult
import com.unifiedledger.application.import.wechat.WechatSourceTokens
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P4-03 spine end-to-end oracle (frozen spec section 1.3, E-01..E-14 plus T-20/T-21/T-22
 * assertions). Parser output drives ExecuteImportIntake per record; confirm/reject reuse
 * the P4-02 ports unchanged. R-01 regression is the untouched
 * ImportSpineLifecycleEndToEndTest (30-op oracle) running in the same suite.
 */
class ImportSpineWechatEndToEndTest {
    private val ledgerId = LedgerId("ledger-p403")
    private val batchLedgerId = LedgerId("ledger-p403-batch")
    private val cny = CurrencyUnit("CNY", 2)
    private val inputRef = "batch-p403-a"
    private val fingerprint = ImportContentFingerprint()

    // ---- Synthetic workbook A (metadata rows, header, W1..W14 data rows) ----
    //
    // The workbook bytes are hand-crafted OOXML (zip + inline-string sheet XML) so this
    // test module stays POI-free (spec section 6: ledger-data zero change). Raw cell
    // texts are written verbatim, giving exact control over the amount decimal texts.

    private sealed interface CellXml
    private data class XmlText(val value: String) : CellXml
    private data class XmlNumber(val raw: String) : CellXml

    private fun columnLetter(index: Int): String = ('A'.code + index).toChar().toString()

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun serialText(date: LocalDate, hour: Int, minute: Int): String {
        val days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date).toDouble()
        return (days + (hour * 3600 + minute * 60) / 86400.0).toString()
    }

    private fun rowCells(
        time: CellXml,
        type: String,
        direction: String,
        amount: CellXml,
        status: String,
        txNo: String? = "SYN-SECRET-TXNO",
        merchNo: String? = "SYN-SECRET-MERCHNO",
    ): List<Pair<Int, CellXml>> = listOfNotNull(
        0 to time, 1 to XmlText(type), 2 to XmlText("SYN-SECRET-COUNTERPARTY"),
        3 to XmlText("SYN-SECRET-PRODUCT"), 4 to XmlText(direction), 5 to amount,
        6 to XmlText("SYN-SECRET-METHOD"), 7 to XmlText(status),
        txNo?.let { 8 to XmlText(it) }, merchNo?.let { 9 to XmlText(it) },
        10 to XmlText("SYN-SECRET-NOTE"),
    )

    private fun workbookA(): ByteArray {
        val sheetRows = mutableListOf<String>()
        for (r in 0..16) {
            sheetRows += rowXml(r, listOf(0 to XmlText("SYN-META-PII-EXPORT-$r"), 1 to XmlText("SYN-META-PII-NICK-$r")))
        }
        sheetRows += rowXml(17, WechatSourceTokens.HEADER_TOKENS.mapIndexed { index, token -> index to XmlText(token) })
        dataRows.forEachIndexed { index, cells -> sheetRows += rowXml(18 + index, cells) }
        val sheetXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetData>" + sheetRows.joinToString("") + "</sheetData></worksheet>"
        val entries = listOf(
            "[Content_Types].xml" to
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>",
            "_rels/.rels" to
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>",
            "xl/workbook.xml" to
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "</Relationships>",
            "xl/worksheets/sheet1.xml" to sheetXml,
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun rowXml(rowIndex: Int, cells: List<Pair<Int, CellXml>>): String =
        "<row r=\"${rowIndex + 1}\">" + cells.joinToString("") { (column, cell) ->
            val ref = "${columnLetter(column)}${rowIndex + 1}"
            when (cell) {
                is XmlText -> "<c r=\"$ref\" t=\"inlineStr\"><is><t>${escapeXml(cell.value)}</t></is></c>"
                is XmlNumber -> "<c r=\"$ref\"><v>${cell.raw}</v></c>"
            }
        } + "</row>"

    private val dataRows: List<List<Pair<Int, CellXml>>> = listOf(
        // W1..W14 in data order; ordinals are derived from the list index.
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 1), 12, 30)), "商户消费", "支出", XmlNumber("128.50"), "支付成功", txNo = null),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 5), 9, 0)), "扫二维码付款", "支出", XmlNumber("12.5"), "支付成功"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 6), 18, 45)), "二维码收款", "收入", XmlNumber("88"), "已存入零钱", merchNo = null),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 8), 10, 0)), "赞赏码", "收入", XmlNumber("3.00"), "已到账"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 9), 21, 15)), "其他", "支出", XmlNumber("45.6"), "支付成功", txNo = null, merchNo = null),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 10), 8, 0)), "商户消费", "/", XmlNumber("0.00"), "支付成功"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 10), 9, 30)), "零钱提现", "支出", XmlNumber("100.00"), "提现已到账"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 11), 11, 0)), "商户消费-退款", "收入", XmlNumber("128.50"), "已退款¥128.50"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 11), 12, 0)), "商户消费", "支出", XmlNumber("10.00"), "已退款(10.00)"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 11), 13, 0)), "商户消费", "出账", XmlNumber("20.00"), "支付成功"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 12), 7, 30)), "商户消费", "支出", XmlText("abc"), "支付成功"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 12), 8, 45)), "神秘交易类型", "支出", XmlNumber("9.90"), "支付成功"),
        rowCells(XmlText("不是时间"), "商户消费", "支出", XmlNumber("10.00"), "支付成功"),
        rowCells(XmlNumber(serialText(LocalDate.of(2026, 8, 12), 9, 0)), "商户消费", "支出", XmlNumber("7.00"), "交易关闭"),
    )

    // ---- Assembly helpers ----

    private fun accepted(rows: List<WechatRowResult>, ordinal: Int): WechatRowResult.Accepted {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<WechatRowResult.Accepted>(row)
    }

    private fun intakeIds(prefix: String, statusId: String) = ImportIntakeIds(
        sourceId = ImportSourceId("source-$prefix"),
        evidenceId = ImportEvidenceId("evidence-$prefix"),
        candidateId = ImportCandidateId("candidate-$prefix"),
        statusHistoryId = ImportStatusHistoryId(statusId),
    )

    private fun commitIds(
        confirmation: String,
        statusId: String,
        tx: String,
        version: String,
        postingSet: String,
        postingIds: List<String>,
    ) = ImportCommitIds(
        confirmationId = ImportConfirmationId(confirmation),
        statusHistoryId = ImportStatusHistoryId(statusId),
        formalIds = ImportFormalIds(
            transactionId = TransactionId(tx),
            versionId = TransactionVersionId(version),
            postingSetId = PostingSetId(postingSet),
            postingIds = postingIds.map(::PostingId),
        ),
    )

    private class BatchIntakeIdSource(private val batches: List<ImportIntakeIds>) : ImportIntakeIdSource {
        val calls = AtomicInteger(0)
        override fun next(): ImportIntakeIds {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "intake id batch exhausted" }
            return batches[index]
        }
    }

    private class BatchCommitIdSource(private val batches: List<ImportCommitIds>) : ImportIdSource {
        val calls = AtomicInteger(0)
        override fun next(): ImportCommitIds {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "commit id batch exhausted" }
            return batches[index]
        }
    }

    private class BatchStatusIdSource(private val batches: List<ImportStatusHistoryId>) : ImportStatusIdSource {
        val calls = AtomicInteger(0)
        override fun next(): ImportStatusHistoryId {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "status id batch exhausted" }
            return batches[index]
        }
    }

    private class OrdinaryFlowFormalFactory(
        private val catalog: LedgerCatalog,
        private val ledgerId: LedgerId,
        private val categoryId: CategoryId,
        private val fundingAccountId: AccountId,
    ) : ImportCandidateFormalFactory {
        private val delegate = com.unifiedledger.application.OrdinaryFlowFormalFactory(catalog)

        override fun create(input: ImportCandidateFormalizationInput, ids: ImportCommitIds): DomainResult<ImportFormalCommit> =
            delegate.create(input, ids)
    }

    private fun catalog(ledgerId: LedgerId): LedgerCatalog = when (
        val result = LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("account-asset-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("expense-account-food"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("income-account-salary"), ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
            ),
            categories = listOf(
                Category(CategoryId("category-primary-food"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-food"), ledgerId, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-primary-salary"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                Category(CategoryId("category-salary"), ledgerId, parentId = CategoryId("category-primary-salary"), postingAccountId = AccountId("income-account-salary"), active = true, kind = CategoryKind.INCOME),
            ),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("wechat e2e catalog failure: ${result.violation}")
    }

    private class Executor(
        val database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        val ledgerId: LedgerId,
        val catalog: LedgerCatalog,
        val intakeIds: ImportIntakeIdSource,
        val commitIds: ImportIdSource,
        val statusIds: ImportStatusIdSource,
    ) {
        val store = SqlDelightImportSpineStore(database, driver)

        fun intake(request: ImportIntakeRequest): ImportIntakeResult =
            ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirm(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult =
            ConfirmImportCandidate(
                store, commitIds,
                OrdinaryFlowFormalFactory(catalog, ledgerId, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).categoryId, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).fundingAccountId),
                catalog,
            ).execute(request)

        fun reject(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult =
            RejectImportCandidate(store, statusIds).execute(request)
    }

    private fun intakeRequest(
        ledgerId: LedgerId,
        requestId: String,
        recordOrdinal: Int,
        facts: com.unifiedledger.application.ImportSourceFacts,
        completeness: ImportCompleteness,
        recordKind: ImportRecordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = recordOrdinal,
        recordKind = recordKind,
        facts = facts,
        completeness = completeness,
        candidateGeneratedAt = "legacy-intake-v1",
    )

    private fun confirmRequest(
        requestId: String = "req-a-confirm",
        candidate: String = "candidate-a",
        hash: String,
        category: String = "category-food",
        funding: String = "account-asset-a",
        confirmedAt: String? = "2026-08-13T10:00:00+08:00",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(
            categoryId = CategoryId(category),
            fundingAccountId = AccountId(funding),
        ),
    )

    private fun rejectRequest(
        requestId: String = "req-b-reject",
        candidate: String = "candidate-b",
        hash: String,
    ) = ImportCandidateRejectRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
    )

    private fun spineCounts(database: LedgerDatabase) = listOf(
        database.ledgerQueries.countImportRequests().executeAsOne(),
        database.ledgerQueries.countImportSourceRecords().executeAsOne(),
        database.ledgerQueries.countImportEvidence().executeAsOne(),
        database.ledgerQueries.countImportCandidates().executeAsOne(),
        database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne(),
        database.ledgerQueries.countImportDecisionSnapshots().executeAsOne(),
        database.ledgerQueries.countImportConfirmations().executeAsOne(),
        database.ledgerQueries.countImportReceipts().executeAsOne(),
    )

    private fun formalCounts(database: LedgerDatabase) = listOf(
        database.ledgerQueries.countTransactions().executeAsOne(),
        database.ledgerQueries.countVersions().executeAsOne(),
        database.ledgerQueries.countPostings().executeAsOne(),
    )

    private fun scalarText(driver: JdbcSqliteDriver, sql: String): String = driver.executeQuery(
        null, sql,
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0)!!)
        },
        0,
    ).value

    // ---- E-01..E-11 ----

    @Test
    fun executesE01ToE11WithStableReplayCollisionAndDomainRetry() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val rows = WechatBillParser.parse(inputRef, workbookA()).rows
            val w1 = accepted(rows, 0)
            val w2 = accepted(rows, 1)
            val w3 = accepted(rows, 2)
            val w4 = accepted(rows, 3)
            val w5 = accepted(rows, 4)
            val catalog = catalog(ledgerId)
            val intakeIds = BatchIntakeIdSource(
                listOf(
                    intakeIds("a", "status-a-1"), intakeIds("b", "status-b-1"), intakeIds("c", "status-c-1"),
                    intakeIds("d", "status-d-1"), intakeIds("e", "status-e-1"),
                ),
            )
            val executor = Executor(
                database, driver, ledgerId, catalog, intakeIds,
                BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()),
            )

            // E-01: parser output drives the intake of W1.
            val e01 = assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-a-intake", 0, w1.facts, w1.completeness)),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.SOURCE, "source-a"),
                    ImportReturnedId(ImportReturnedIdKind.EVIDENCE, "evidence-a"),
                    ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-a"),
                ),
                e01.returnedIds,
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-a-intake"), ImportSourceId("source-a"), ImportEvidenceId("evidence-a"), ImportCandidateId("candidate-a"), null, null),
                e01.receipt,
            )
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w1.facts), database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-a-intake").executeAsOne().content_hash)

            // E-02: same-request equivalent replay.
            val e02 = assertIs<ImportIntakeResult.NoChange>(
                executor.intake(intakeRequest(ledgerId, "req-a-intake", 0, w1.facts, w1.completeness)),
            )
            assertEquals(e01.receipt, e02.receipt)
            assertEquals("equivalent_replay", e02.reasonCode)
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // E-03: confirm C1 -> formal expense transaction with W1 amounts.
            val commitIdsA = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            val executorWithCommit = Executor(database, driver, ledgerId, catalog, intakeIds, commitIdsA, BatchStatusIdSource(emptyList()))
            val e03 = assertIs<ImportCandidateDecisionResult.Accepted>(
                executorWithCommit.confirm(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w1.facts))),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-a"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-a"),
                ),
                e03.returnedIds,
            )
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            val postingsA = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-a").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-expense-a", "expense-account-food", 12850L, "CNY", 2L),
                    listOf("posting-asset-a", "account-asset-a", -12850L, "CNY", 2L),
                ),
                postingsA.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )

            // E-04: same-request confirm replay.
            val e04 = assertIs<ImportCandidateDecisionResult.NoChange>(
                executorWithCommit.confirm(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w1.facts))),
            )
            assertEquals(e03.receipt, e04.receipt)
            assertEquals("equivalent_replay", e04.reasonCode)
            assertEquals(1, commitIdsA.calls.get())

            // E-05: re-confirm with a new request.
            val e05 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executorWithCommit.confirm(confirmRequest(requestId = "req-a-confirm-2", hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w1.facts))),
            )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", e05.diagnostic.code)

            // E-06: setup intakes for W2..W5.
            assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest(ledgerId, "req-b-intake", 1, w2.facts, w2.completeness)))
            assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest(ledgerId, "req-c-intake", 2, w3.facts, w3.completeness)))
            assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest(ledgerId, "req-d-intake", 3, w4.facts, w4.completeness)))
            assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest(ledgerId, "req-e-intake", 4, w5.facts, w5.completeness)))
            assertEquals(listOf(6L, 5L, 5L, 5L, 6L, 1L, 1L, 6L), spineCounts(database))

            // E-07: reject C2 (manual disposition).
            val statusIds = BatchStatusIdSource(listOf(ImportStatusHistoryId("status-b-2")))
            val executorWithReject = Executor(database, driver, ledgerId, catalog, intakeIds, commitIdsA, statusIds)
            val e07 = assertIs<ImportCandidateDecisionResult.Accepted>(
                executorWithReject.reject(rejectRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w2.facts))),
            )
            assertEquals(listOf(ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-b")), e07.returnedIds)
            assertEquals(
                ImportReceipt(ImportRequestId("req-b-reject"), null, null, ImportCandidateId("candidate-b"), null, null),
                e07.receipt,
            )
            assertEquals(listOf(7L, 5L, 5L, 5L, 7L, 2L, 1L, 7L), spineCounts(database))

            // E-08: W1' intake-level fixture with the same raw identity but a different
            // amount: hard identity collision with zero writes.
            val e08 = assertIs<ImportIntakeResult.Rejected>(
                executor.intake(intakeRequest(ledgerId, "req-a-intake-3", 0, w1.facts.copy(amountMinor = 12851), w1.completeness)),
            )
            assertEquals("SPINE_IDENTITY_COLLISION", e08.diagnostic.code)
            assertEquals(listOf(7L, 5L, 5L, 5L, 7L, 2L, 1L, 7L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))

            // E-10: confirm C4 with an unknown category -> domain failure, zero residue.
            val attempt1 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-b-attempt-1", "status-d-2-attempt-1", "tx-b-attempt-1",
                        "version-d-attempt-1-v1", "posting-set-d-attempt-1",
                        listOf("posting-asset-d-attempt-1", "posting-income-d-attempt-1"),
                    ),
                ),
            )
            val e10 = assertIs<ImportCandidateDecisionResult.Rejected>(
                Executor(database, driver, ledgerId, catalog, intakeIds, attempt1, statusIds).confirm(
                    confirmRequest(
                        requestId = "req-d-confirm", candidate = "candidate-d", hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w4.facts),
                        category = "category-unknown", confirmedAt = "2026-08-13T11:00:00+08:00",
                    ),
                ),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e10.diagnostic.code)
            assertEquals(1, attempt1.calls.get())
            assertEquals(listOf(7L, 5L, 5L, 5L, 7L, 2L, 1L, 7L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))

            // E-11: corrected retry on the same request identity -> accepted income.
            val batch2 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-b", "status-d-2", "tx-b",
                        "version-d-v1", "posting-set-d",
                        listOf("posting-asset-d", "posting-income-d"),
                    ),
                ),
            )
            val e11 = assertIs<ImportCandidateDecisionResult.Accepted>(
                Executor(database, driver, ledgerId, catalog, intakeIds, batch2, statusIds).confirm(
                    confirmRequest(
                        requestId = "req-d-confirm", candidate = "candidate-d", hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w4.facts),
                        category = "category-salary", confirmedAt = "2026-08-13T11:00:00+08:00",
                    ),
                ),
            )
            assertEquals(1, batch2.calls.get())
            assertEquals(listOf(8L, 5L, 5L, 5L, 8L, 3L, 2L, 8L), spineCounts(database))
            assertEquals(listOf(2L, 2L, 4L), formalCounts(database))
            val postingsD = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-d").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-asset-d", "account-asset-a", 300L, "CNY", 2L),
                    listOf("posting-income-d", "income-account-salary", -300L, "CNY", 2L),
                ),
                postingsD.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )
        } finally {
            driver.close()
        }
    }

    // ---- E-09 concurrency ----

    @Test
    fun e09ConcurrentIntakesCommitOnceWithoutLoserResidue() {
        val path = Files.createTempFile("wechat-intake-e09-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            val w1 = accepted(WechatBillParser.parse(inputRef, workbookA()).rows, 0)
            val results = concurrentExecute(
                url,
                listOf(
                    { intakeOn(url, w1) },
                    { intakeOn(url, w1) },
                ),
            )
            assertEquals(1, results.count { it is ImportIntakeResult.Accepted })
            assertEquals(1, results.count { it is ImportIntakeResult.NoChange })
            JdbcSqliteDriver(url).use { driver ->
                assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(LedgerDatabase(driver)))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---- E-12 batch ledger + T-21 privacy ----

    @Test
    fun e12BatchLedgerIntakesNineRecordsAndRejectsFiveRowsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val result = WechatBillParser.parse(inputRef, workbookA())
            assertEquals(WechatBatchOutcome.PARTIAL, result.outcome)
            val acceptedRows = result.rows.filterIsInstance<WechatRowResult.Accepted>()
            val rejectedRows = result.rows.filterIsInstance<WechatRowResult.Rejected>()
            assertEquals(9, acceptedRows.size)
            assertEquals(5, rejectedRows.size)
            assertEquals(8, result.rows.flatMap { it.diagnostics }.size)

            val batches = acceptedRows.map { row -> intakeIds("w${row.recordOrdinal}", "status-w${row.recordOrdinal}-1") }
            val executor = Executor(
                database, driver, batchLedgerId, catalog(batchLedgerId),
                BatchIntakeIdSource(batches), BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()),
            )
            acceptedRows.forEach { row ->
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest(batchLedgerId, "req-batch-${row.recordOrdinal}", row.recordOrdinal, row.facts, row.completeness, row.recordKind)),
                )
            }
            // Rejected rows produced no intake call and no write: only nine owners exist.
            assertEquals(listOf(9L, 9L, 9L, 9L, 9L, 0L, 0L, 9L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))

            // C1..C5 (ordinals 0..4) pending_confirmation; C6 (W6, ordinal 5), C7 (W7,
            // ordinal 6) transfer pending, C8 (W10, ordinal 9) and C9 (W14, ordinal 13) incomplete.
            listOf(0, 1, 2, 3, 4, 6).forEach { ordinal ->
                val history = database.ledgerQueries.selectImportStatusHistoryByCandidate(batchLedgerId.value, "candidate-w$ordinal").executeAsList()
                assertEquals(1, history.size)
                assertEquals("pending_confirmation", history[0].status)
            }
            listOf(5, 9, 13).forEach { ordinal ->
                val history = database.ledgerQueries.selectImportStatusHistoryByCandidate(batchLedgerId.value, "candidate-w$ordinal").executeAsList()
                assertEquals(1, history.size)
                assertEquals("incomplete", history[0].status)
            }

            // T-21 privacy: the non-persisted columns (counterparty/product/method/order
            // ids/note) and the metadata area never reach any persisted column.
            val leaked = scalarText(
                driver,
                "SELECT COUNT(*) FROM import_source_record WHERE ledger_id = '${batchLedgerId.value}' AND (" +
                    "input_ref LIKE '%SYN-%' OR content_hash LIKE '%SYN-%' OR currency_code LIKE '%SYN-%' OR " +
                    "occurred_at LIKE '%SYN-%' OR direction_token LIKE '%SYN-%' OR status_token LIKE '%SYN-%')",
            )
            assertEquals("0", leaked)
            assertTrue(rejectedRows.all { row -> row.diagnostics.all { it.inputRef == inputRef } })
        } finally {
            driver.close()
        }
    }

    // ---- E-13/E-14 failure injection ----

    @Test
    fun e13E14InjectedFailuresRollBackAndCorrectedRetriesAccept() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog(ledgerId)
            val w1 = accepted(WechatBillParser.parse(inputRef, workbookA()).rows, 0)

            // E-13: intake failure after the candidate insert.
            val failingStore = SqlDelightImportSpineStore(
                database, driver,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE) error("injected") },
            )
            val batch1 = BatchIntakeIdSource(listOf(intakeIds("a-attempt-1", "status-a-1-attempt-1")))
            assertFailsWith<IllegalStateException> {
                ExecuteImportIntake(failingStore, batch1, ImportContentFingerprint()).execute(
                    intakeRequest(ledgerId, "req-a-intake", 0, w1.facts, w1.completeness),
                )
            }
            assertEquals(1, batch1.calls.get())
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), spineCounts(database))
            val batch2 = BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), batch2, ImportContentFingerprint()).execute(
                    intakeRequest(ledgerId, "req-a-intake", 0, w1.facts, w1.completeness),
                ),
            )
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // E-14: confirm failure after the formal persist.
            val failingConfirmStore = SqlDelightImportSpineStore(
                database, driver,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL) error("injected") },
            )
            val attempt1 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-a-attempt-1", "status-a-2-attempt-1", "tx-a-attempt-1",
                        "version-a-attempt-1-v1", "posting-set-a-attempt-1",
                        listOf("posting-expense-a-attempt-1", "posting-asset-a-attempt-1"),
                    ),
                ),
            )
            assertFailsWith<IllegalStateException> {
                ConfirmImportCandidate(
                    failingConfirmStore, attempt1,
                    OrdinaryFlowFormalFactory(catalog, ledgerId, CategoryId("category-food"), AccountId("account-asset-a")),
                    catalog,
                ).execute(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w1.facts)))
            }
            assertEquals(1, attempt1.calls.get())
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            val confirmBatch2 = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver), confirmBatch2,
                    OrdinaryFlowFormalFactory(catalog, ledgerId, CategoryId("category-food"), AccountId("account-asset-a")),
                    catalog,
                ).execute(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, w1.facts))),
            )
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
        } finally {
            driver.close()
        }
    }

    // ---- Concurrency plumbing (P4-02 test pattern) ----

    private fun intakeOn(url: String, row: WechatRowResult.Accepted): ImportIntakeResult =
        JdbcSqliteDriver(url).use { driver ->
            val database = LedgerDatabase(driver)
            ExecuteImportIntake(
                SqlDelightImportSpineStore(database, driver),
                BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1"))),
                ImportContentFingerprint(),
            ).execute(intakeRequest(ledgerId, "req-a-intake", row.recordOrdinal, row.facts, row.completeness))
        }

    private fun concurrentExecute(url: String, operations: List<() -> Any>): List<Any> {
        val pool = Executors.newFixedThreadPool(operations.size)
        val ready = CountDownLatch(operations.size)
        val start = CountDownLatch(1)
        return try {
            val futures = operations.map { operation ->
                pool.submit<Any> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    operation()
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
