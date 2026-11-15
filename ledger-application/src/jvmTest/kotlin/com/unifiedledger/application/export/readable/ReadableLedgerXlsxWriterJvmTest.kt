package com.unifiedledger.application.export.readable

import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.export.readable.parseReadableExportWallClockTextOrNull
import com.unifiedledger.application.export.readable.readableExportAmountText
import com.unifiedledger.application.export.readable.readableExportWallClockText
import com.unifiedledger.application.import.wechat.BoundedXlsxReader
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.unifiedledger.domain.LedgerId as DomainLedgerId

/**
 * P8-05 批 a (V-1/V-2/V-5; D-331 spec §5): the streaming writer's structure and round-trip
 * evidence, driven end-to-end through the REAL [ReadableLedgerExport] use case, the REAL
 * [ReadableLedgerXlsxStreamWriter] and the REAL [BoundedXlsxReader]:
 *
 * - structure: the zip part list is EXACTLY the frozen six parts (zero docProps, zero styles,
 *   no sharedStrings — D-E3/D-E7), every entry timestamp is the DOS epoch, the header row is
 *   the single constant source, and every text cell is inlineStr (the registered R9 vector:
 *   the template's sharedStrings-only products never exercised this read-back face);
 * - round-trip: one synthetic ledger covering every D-E5 group resolves, reads back and matches
 *   column by column; both time columns parse back to the original instants (V-2, including the
 *   month-boundary 00:00 vector);
 * - V-5: no cell carries a UUID-shaped id, a bare synthetic id, a URI or the ledger id; the
 *   unnamed account/category land on the fixed placeholders (D-E7);
 * - failure family (V-4): ENOSPC-style sink failure, oversized notes on both legs, cancellation,
 *   RUNTIME_NOT_READY, typed read failures — none ever commits, none reports success.
 */
class ReadableLedgerXlsxWriterJvmTest {
    private val clock = LedgerClock { Instant.parse("2026-11-15T02:00:00Z") }
    private val ledgerId = LedgerId("ledger-readable-export")

    /** 2026-03-01T02:00:00Z in epoch millis — the 50k walk's strict whole-second base. */
    private const val START_EPOCH_MILLIS = 1_772_332_000_000L

    // ---------------------------------------------------------------- structure + round-trip

    @Test
    fun thePackageCarriesExactlyTheFrozenSixPartsWithDosEpochTimestampsAndNoDocProps() {
        val outcome = runExport(rows = listOf(expenseRow("tx-a", Instant.parse("2026-03-02T02:00:00Z"))), catalog = groupCatalog())
        assertIs<ReadableLedgerExportResult.Succeeded>(outcome.result)

        val entries = zipEntries(outcome.sink.buffer.toByteArray())
        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml"),
            entries.map { it.name },
        )
        entries.forEach { entry ->
            assertEquals(0L, entry.time, "zip entry ${entry.name} must be frozen at the DOS epoch")
        }
        val sheet = entries.last { it.name == "xl/worksheets/sheet1.xml" }.content
        assertTrue(sheet.contains("t=\"inlineStr\""), "text cells must be inlineStr (D-E3)")
        assertTrue(sheet.contains(READABLE_EXPORT_SHEET_NAME), "workbook sheet name is the frozen display copy")
        assertTrue(!sheet.contains("t=\"s\""), "no shared-string cells exist (D-E3 drops the pool)")
        val workbookRels = entries.first { it.name == "xl/_rels/workbook.xml.rels" }.content
        assertTrue(!workbookRels.contains("sharedStrings"), "no sharedStrings relationship (D-E3)")
        entries.forEach { entry -> assertTrue(!entry.content.contains("docProps"), "zero docProps (D-E7): ${entry.name}") }
    }

    @Test
    fun theHeaderRowIsTheSingleConstantSourceAndRoundTripsThroughTheReader() {
        val outcome = runExport(rows = listOf(expenseRow("tx-a", Instant.parse("2026-03-02T02:00:00Z"))), catalog = groupCatalog())
        assertIs<ReadableLedgerExportResult.Succeeded>(outcome.result)

        val workbook = BoundedXlsxReader.read(outcome.sink.buffer.toByteArray())
        assertEquals(1, workbook.numberOfSheets)
        val header = workbook.firstSheet.getRow(0)
        assertNotNull(header)
        READABLE_EXPORT_HEADERS.forEachIndexed { column, expected ->
            val cell = header.getCell(column)
            assertNotNull(cell, "header cell ${column + 1} missing")
            assertEquals(BoundedXlsxReader.CellType.STRING, cell.type)
            assertEquals(expected, cell.string)
        }
        assertNull(header.getCell(READABLE_EXPORT_HEADERS.size), "no column beyond J")
    }

    @Test
    fun everyPresentationGroupRoundTripsColumnByColumn() {
        val catalog = groupCatalog()
        val rows =
            listOf(
                // ① EXPENSE: category leg +35.80 (golden rg-01:1660), fund leg −35.80.
                legsRow(
                    "tx-expense",
                    TransactionKind.EXPENSE,
                    "2026-01-15T02:14:40Z",
                    "2026-01-15T02:14:40Z",
                    note = "早餐",
                    Posting(PostingId("p-e0"), AccountId("acc-expense-food"), Money.ofMinor(3_580L, CNY)),
                    Posting(PostingId("p-e1"), AccountId("acc-bank"), Money.ofMinor(-3_580L, CNY)),
                ),
                // ① INCOME: category leg −2000.00 (golden rg-10:663), fund leg +2000.00.
                legsRow(
                    "tx-income",
                    TransactionKind.INCOME,
                    "2026-01-20T02:30:00Z",
                    "2026-01-20T02:30:00Z",
                    note = null,
                    Posting(PostingId("p-i0"), AccountId("acc-income-salary"), Money.ofMinor(-200_000L, CNY)),
                    Posting(PostingId("p-i1"), AccountId("acc-bank"), Money.ofMinor(200_000L, CNY)),
                ),
                // ② ACCOUNT_TRANSFER + fee: outflow −500.00 carries the amount; fee category leg.
                legsRow(
                    "tx-transfer",
                    TransactionKind.ACCOUNT_TRANSFER,
                    "2026-01-21T03:00:00Z",
                    "2026-01-21T03:00:00Z",
                    note = null,
                    Posting(PostingId("p-t0"), AccountId("acc-bank"), Money.ofMinor(-50_000L, CNY)),
                    Posting(PostingId("p-t1"), AccountId("acc-wallet"), Money.ofMinor(49_900L, CNY)),
                    Posting(PostingId("p-t2"), AccountId("acc-fee"), Money.ofMinor(100L, CNY)),
                ),
                // ③ BALANCE_ADJUSTMENT: asset leg +30.00 (golden rg-09:328-340), equity −30.00.
                legsRow(
                    "tx-adjust",
                    TransactionKind.BALANCE_ADJUSTMENT,
                    "2026-01-22T03:00:00Z",
                    "2026-01-22T03:00:00Z",
                    note = null,
                    Posting(PostingId("p-a0"), AccountId("acc-bank"), Money.ofMinor(3_000L, CNY)),
                    Posting(PostingId("p-a1"), AccountId("acc-equity"), Money.ofMinor(-3_000L, CNY)),
                ),
                // ③ BALANCE_ADJUSTMENT_REVERSAL: asset leg −20.00 (golden rg-09:364-376).
                legsRow(
                    "tx-reversal",
                    TransactionKind.BALANCE_ADJUSTMENT_REVERSAL,
                    "2026-01-23T03:00:00Z",
                    "2026-01-23T03:00:00Z",
                    note = null,
                    Posting(PostingId("p-r0"), AccountId("acc-bank"), Money.ofMinor(-2_000L, CNY)),
                    Posting(PostingId("p-r1"), AccountId("acc-equity"), Money.ofMinor(2_000L, CNY)),
                ),
                // ④ OPENING_BALANCE: asset leg +1000.00 (golden rg-01:175-188), equity −1000.00.
                legsRow(
                    "tx-opening",
                    TransactionKind.OPENING_BALANCE,
                    "2025-12-31T16:00:00Z",
                    "2025-12-31T16:00:00Z",
                    note = null,
                    Posting(PostingId("p-o0"), AccountId("acc-bank"), Money.ofMinor(100_000L, CNY)),
                    Posting(PostingId("p-o1"), AccountId("acc-equity"), Money.ofMinor(-100_000L, CNY)),
                ),
                // ⑤ STORED_VALUE_SPEND: category leg +300.00 (golden rg-10:1745), asset −300.00.
                legsRow(
                    "tx-spend",
                    TransactionKind.STORED_VALUE_SPEND,
                    "2026-01-25T03:00:00Z",
                    "2026-01-25T03:00:00Z",
                    note = null,
                    Posting(PostingId("p-s0"), AccountId("acc-expense-food"), Money.ofMinor(30_000L, CNY)),
                    Posting(PostingId("p-s1"), AccountId("acc-wallet"), Money.ofMinor(-30_000L, CNY)),
                ),
                // ⑦ REFUND_RECEIPT: negative category leg (负支出, MonthlyBuckets semantics).
                legsRow(
                    "tx-refund",
                    TransactionKind.REFUND_RECEIPT,
                    "2026-01-26T03:00:00Z",
                    "2026-01-26T03:00:00Z",
                    note = null,
                    Posting(PostingId("p-f0"), AccountId("acc-expense-food"), Money.ofMinor(-1_200L, CNY)),
                    Posting(PostingId("p-f1"), AccountId("acc-bank"), Money.ofMinor(1_200L, CNY)),
                ),
                // ⑥ PREPAID_RECOGNITION: category leg +33.33 (golden rg-11:901-919), prepaid −33.33.
                legsRow(
                    "tx-recognition",
                    TransactionKind.PREPAID_RECOGNITION,
                    "2026-01-31T07:33:20Z",
                    "2026-01-31T07:33:20Z",
                    note = null,
                    Posting(PostingId("p-c0"), AccountId("acc-expense-service"), Money.ofMinor(3_333L, CNY)),
                    Posting(PostingId("p-c1"), AccountId("acc-prepaid"), Money.ofMinor(-3_333L, CNY)),
                ),
                // 无名账户 + 无映射分类腿: the D-E7 placeholder row (the account has no catalog row).
                legsRow(
                    "tx-unnamed",
                    TransactionKind.EXPENSE,
                    "2026-02-01T00:00:00Z",
                    "2026-02-01T00:00:00Z",
                    note = "边界",
                    Posting(PostingId("p-u0"), AccountId("acc-ghost"), Money.ofMinor(500L, CNY)),
                    Posting(PostingId("p-u1"), AccountId("acc-bank"), Money.ofMinor(-500L, CNY)),
                ),
                // 双时间分列: occurred 与 statistics 落在不同上海自然日.
                legsRow(
                    "tx-times",
                    TransactionKind.EXPENSE,
                    "2026-01-15T10:30:00Z",
                    "2026-02-01T00:30:00Z",
                    note = null,
                    Posting(PostingId("p-x0"), AccountId("acc-expense-food"), Money.ofMinor(800L, CNY)),
                    Posting(PostingId("p-x1"), AccountId("acc-bank"), Money.ofMinor(-800L, CNY)),
                ),
            )
        val outcome = runExport(rows = rows, catalog = catalog)
        val succeeded = assertIs<ReadableLedgerExportResult.Succeeded>(outcome.result)
        assertEquals(rows.size.toLong(), succeeded.exportedRows)
        assertEquals(rows.size, succeeded.totalRows)

        val workbook = BoundedXlsxReader.read(outcome.sink.buffer.toByteArray())
        val sheet = workbook.firstSheet
        assertEquals(rows.size, sheet.lastRowNum, "one row per transaction beyond the header")

        // ① 支出: A/B 双时间、C 类型中文名、D 精确十进制原符号正数、F/G 账户、H 完整路径、I 备注、J 来源.
        var row = sheet.getRow(1)
        assertNotNull(row)
        assertEquals("2026-01-15 10:14:40", row.getCell(0)!!.string)
        assertEquals("2026-01-15 10:14:40", row.getCell(1)!!.string)
        assertEquals("支出", row.getCell(2)!!.string)
        assertEquals(BoundedXlsxReader.CellType.NUMERIC, row.getCell(3)!!.type)
        assertEquals("35.80", row.getCell(3)!!.rawValue)
        assertEquals("CNY", row.getCell(4)!!.string)
        assertEquals("餐饮支出", row.getCell(5)!!.string)
        assertEquals("招商银行", row.getCell(6)!!.string)
        assertEquals("餐饮/早餐", row.getCell(7)!!.string)
        assertEquals("早餐", row.getCell(8)!!.string)
        assertEquals("来源未标注", row.getCell(9)!!.string)

        // ① 收入: 原符号负数（不翻转）.
        row = sheet.getRow(2)
        assertNotNull(row)
        assertEquals("收入", row.getCell(2)!!.string)
        assertEquals("-2000.00", row.getCell(3)!!.rawValue)
        assertEquals("工资收入", row.getCell(5)!!.string)
        assertEquals("招商银行", row.getCell(6)!!.string)
        assertEquals("收入/工资", row.getCell(7)!!.string)

        // ② 转账: 金额取流出腿 −500.00，账户2 = 流入方，分类锚定手续费腿.
        row = sheet.getRow(3)
        assertNotNull(row)
        assertEquals("转账", row.getCell(2)!!.string)
        assertEquals("-500.00", row.getCell(3)!!.rawValue)
        assertEquals("招商银行", row.getCell(5)!!.string)
        assertEquals("零钱", row.getCell(6)!!.string)
        assertEquals("手续费", row.getCell(7)!!.string)

        // ③ 余额调整: 资产腿 +30.00；冲正 −20.00；分类空.
        row = sheet.getRow(4)
        assertNotNull(row)
        assertEquals("余额调整", row.getCell(2)!!.string)
        assertEquals("30.00", row.getCell(3)!!.rawValue)
        assertEquals("招商银行", row.getCell(5)!!.string)
        assertEquals("期初权益", row.getCell(6)!!.string)
        assertEquals("", row.getCell(7)!!.string)
        row = sheet.getRow(5)
        assertNotNull(row)
        assertEquals("余额调整冲正", row.getCell(2)!!.string)
        assertEquals("-20.00", row.getCell(3)!!.rawValue)

        // ④ 期初余额: 资产腿 +1000.00，统计时间落在上海 2026-01-01 00:00（月界 00:00 向量）.
        row = sheet.getRow(6)
        assertNotNull(row)
        assertEquals("期初余额", row.getCell(2)!!.string)
        assertEquals("1000.00", row.getCell(3)!!.rawValue)
        assertEquals("2026-01-01 00:00:00", row.getCell(1)!!.string)
        assertEquals(YearMonth(2026, 1), MonthlyBuckets.bucketKey(Instant.parse("2025-12-31T16:00:00Z")))

        // ⑤ 储值消费: 分类腿 +300.00.
        row = sheet.getRow(7)
        assertNotNull(row)
        assertEquals("储值消费", row.getCell(2)!!.string)
        assertEquals("300.00", row.getCell(3)!!.rawValue)
        assertEquals("零钱", row.getCell(6)!!.string)

        // ⑦ 退款到账: 负支出原符号 −12.00.
        row = sheet.getRow(8)
        assertNotNull(row)
        assertEquals("退款到账", row.getCell(2)!!.string)
        assertEquals("-12.00", row.getCell(3)!!.rawValue)

        // ⑥ 预付确认: +33.33，账户2 = prepaid 资产.
        row = sheet.getRow(9)
        assertNotNull(row)
        assertEquals("预付确认", row.getCell(2)!!.string)
        assertEquals("33.33", row.getCell(3)!!.rawValue)
        assertEquals("预付资产", row.getCell(6)!!.string)

        // 无名账户占位 + 未映射分类空 + 占位不落裸 id.
        row = sheet.getRow(10)
        assertNotNull(row)
        assertEquals("支出", row.getCell(2)!!.string)
        assertEquals("5.00", row.getCell(3)!!.rawValue)
        assertEquals(READABLE_EXPORT_UNNAMED_ACCOUNT, row.getCell(5)!!.string)
        assertEquals("招商银行", row.getCell(6)!!.string)
        assertEquals("", row.getCell(7)!!.string)

        // 双时间分列: 凭证 2026-01-15 18:30 / 统计 2026-02-01 08:30.
        row = sheet.getRow(11)
        assertNotNull(row)
        assertEquals("2026-01-15 18:30:00", row.getCell(0)!!.string)
        assertEquals("2026-02-01 08:30:00", row.getCell(1)!!.string)
    }

    @Test
    fun theUnresolvedPresentationRowCarriesThePlaceholderTextAndNeverCommits() {
        val catalog = groupCatalog()
        // ① 兜底腿形: 两条分类腿 → D-E5 表未覆盖 → 行内占位并计数，整体 PRESENTATION_UNRESOLVED.
        val rows =
            listOf(
                legsRow(
                    "tx-multi-cat",
                    TransactionKind.EXPENSE,
                    "2026-01-15T02:14:40Z",
                    "2026-01-15T02:14:40Z",
                    note = null,
                    Posting(PostingId("p-m0"), AccountId("acc-expense-food"), Money.ofMinor(100L, CNY)),
                    Posting(PostingId("p-m1"), AccountId("acc-expense-service"), Money.ofMinor(2_000L, CNY)),
                    Posting(PostingId("p-m2"), AccountId("acc-bank"), Money.ofMinor(-2_100L, CNY)),
                ),
            )
        val outcome = runExport(rows = rows, catalog = catalog)
        val failed = assertIs<ReadableLedgerExportResult.Failed>(outcome.result)
        assertEquals(ReadableLedgerExportFailure.PRESENTATION_UNRESOLVED, failed.reason)
        assertEquals(0, outcome.sink.commitCount, "a data-condition stop never commits (V-4)")

        val workbook = BoundedXlsxReader.read(outcome.sink.buffer.toByteArray())
        val row = workbook.firstSheet.getRow(1)
        assertNotNull(row)
        assertEquals("支出", row.getCell(2)!!.string)
        assertEquals(BoundedXlsxReader.CellType.STRING, row.getCell(3)!!.type)
        assertEquals("（呈现未定）", row.getCell(3)!!.string)
        assertEquals("", row.getCell(4)!!.string)
        assertEquals("", row.getCell(5)!!.string)
    }

    @Test
    fun theTimeColumnsRoundTripToTheOriginalInstantsIncludingTheMonthBoundary() {
        // 2026-01-31T16:00:00Z = 上海 2026-02-01 00:00（月界 00:00 归属新月）；跨日边界同一向量.
        val boundary = Instant.parse("2026-01-31T16:00:00Z")
        val endOfDay = Instant.parse("2026-03-01T15:59:59Z")
        assertEquals("2026-02-01 00:00:00", readableExportWallClockText(boundary))
        assertEquals("2026-03-01 23:59:59", readableExportWallClockText(endOfDay))
        assertEquals(boundary, parseReadableExportWallClockTextOrNull("2026-02-01 00:00:00"))
        assertEquals(endOfDay, parseReadableExportWallClockTextOrNull("2026-03-01 23:59:59"))
        val early = Instant.parse("0999-01-02T03:04:05Z")
        assertEquals("0999-01-02 11:04:05", readableExportWallClockText(early))
        assertEquals(early, parseReadableExportWallClockTextOrNull("0999-01-02 11:04:05"))
        assertNull(parseReadableExportWallClockTextOrNull("2026-02-30 00:00:00"), "impossible dates stay null")
    }

    @Test
    fun noCellCarriesAnOutOfScopeIdentifierAndTheSuggestedNameCarriesNoId() {
        val catalog = groupCatalog()
        val rows =
            listOf(
                legsRow(
                    "0e5c1e2a-7c1d-4f0e-9a1b-2c3d4e5f6a7b",
                    TransactionKind.EXPENSE,
                    "2026-01-15T02:14:40Z",
                    "2026-01-15T02:14:40Z",
                    note = null,
                    Posting(PostingId("3f2b1a0e-1111-2222-3333-444455556666"), AccountId("acc-expense-food"), Money.ofMinor(3_580L, CNY)),
                    Posting(PostingId("3f2b1a0e-7777-8888-9999-aaaabbbbcccc"), AccountId("acc-bank"), Money.ofMinor(-3_580L, CNY)),
                ),
            )
        val outcome = runExport(rows = rows, catalog = catalog)
        val succeeded = assertIs<ReadableLedgerExportResult.Succeeded>(outcome.result)
        assertEquals("unifiedledger-export-20261115.xlsx", succeeded.suggestedFileName)
        assertTrue(succeeded.suggestedFileName.startsWith(READABLE_EXPORT_SUGGESTED_NAME_PREFIX))

        val workbook = BoundedXlsxReader.read(outcome.sink.buffer.toByteArray())
        val sheet = workbook.firstSheet
        val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val bannedSubstrings = listOf("tx-", "p-", "acc-", "ledger-", "://", "readableExport")
        for (rowIndex in 0..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex)
            assertNotNull(row)
            for (column in 0..9) {
                val cell = row.getCell(column) ?: continue
                val text = cell.string ?: cell.rawValue ?: continue
                assertNull(uuid.find(text), "UUID-shaped identifier in cell ${rowIndex + 1}:${column + 1}: $text")
                bannedSubstrings.forEach { banned ->
                    assertTrue(!text.contains(banned), "out-of-scope token '$banned' in cell ${rowIndex + 1}:${column + 1}: $text")
                }
            }
        }
    }

    @Test
    fun anEmptyLedgerProducesTheHeaderOnlyWorkbookAndAZeroRowCount() {
        val outcome = runExport(rows = emptyList(), catalog = groupCatalog())
        val succeeded = assertIs<ReadableLedgerExportResult.Succeeded>(outcome.result)
        assertEquals(0L, succeeded.exportedRows)
        assertEquals(0, succeeded.totalRows)

        val workbook = BoundedXlsxReader.read(outcome.sink.buffer.toByteArray())
        assertEquals(0, workbook.firstSheet.lastRowNum, "the empty ledger is the legal header-only file (D-E10)")
    }

    // ---------------------------------------------------------------- failure family (V-4)

    @Test
    fun anOversizedNoteOnTheFirstPageFailsBeforeTheTargetOpens() {
        val oversized = expenseRow("tx-big", Instant.parse("2026-03-02T02:00:00Z"), note = "好".repeat(32_768))
        val harness = ExportHarness.build(rows = listOf(oversized), pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        val result = harness.export.export(ledgerId)
        val failed = assertIs<ReadableLedgerExportResult.Failed>(result)
        assertEquals(ReadableLedgerExportFailure.NOTE_TOO_LONG, failed.reason)
        assertNull(harness.openedWriter, "the zero-artifact precheck leg never opens the target")
    }

    @Test
    fun anOversizedNoteMidWalkStopsWithoutCommitAndClosesTheWriter() {
        val ok = expenseRow("tx-ok-1", Instant.parse("2026-03-02T02:00:00Z"))
        val ok2 = expenseRow("tx-ok-2", Instant.parse("2026-03-03T02:00:00Z"))
        val oversized = expenseRow("tx-big", Instant.parse("2026-03-04T02:00:00Z"), note = "长".repeat(32_768))
        val harness = ExportHarness.build(rows = listOf(ok, ok2, oversized), pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        val writer = harness.defaultWriter()
        val result = harness.export.export(ledgerId)
        val failed = assertIs<ReadableLedgerExportResult.Failed>(result)
        assertEquals(ReadableLedgerExportFailure.NOTE_TOO_LONG, failed.reason)
        assertEquals(0, writer.commitCount, "the mid-walk stop never commits (V-4)")
        assertTrue(writer.closeCount >= 1, "the writer closed cleanly on the stop path")
    }

    @Test
    fun aSinkFailureMidStreamIsTheTypedWriteFailedAndNeverCommits() {
        val rows = (1..6).map { index -> expenseRow("tx-$index", Instant.parse("2026-03-0${index + 1}T02:00:00Z")) }
        val harness = ExportHarness.build(rows = rows, pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        val failingSink =
            RecordingSink(
                failAfterBytes = 64,
                failure = java.io.IOException("ENOSPC simulated: no space left on device"),
            )
        val writer = ReadableLedgerXlsxStreamWriter(failingSink)
        val result = harness.exportWithWriterProvider { writer }.export(ledgerId)
        val failed = assertIs<ReadableLedgerExportResult.Failed>(result)
        assertEquals(ReadableLedgerExportFailure.WRITE_FAILED, failed.reason)
        assertEquals(0, failingSink.commitCount, "the sink failure never reaches commit (V-4)")
    }

    @Test
    fun aNullWriterIsTheUserCancelledOutcomeAndNothingStreams() {
        val harness = ExportHarness.build(rows = listOf(expenseRow("tx-a", Instant.parse("2026-03-02T02:00:00Z"))), pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        val result = harness.exportWithWriterProvider { null }.export(ledgerId)
        assertEquals(ReadableLedgerExportResult.Cancelled, result)
    }

    @Test
    fun aCancellationSignalMidWalkStopsWithoutCommit() {
        val rows = (1..6).map { index -> expenseRow("tx-$index", Instant.parse("2026-03-0${index + 1}T02:00:00Z")) }
        val harness = ExportHarness.build(rows = rows, pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        val writer = RecordingSheetWriter()
        val export =
            ReadableLedgerExport(
                leasePort = harness.leasePort,
                flowPage = harness.flowPage,
                creationEntries = null,
                catalogProvider = { groupCatalog() },
                writerProvider = { writer },
                clock = clock,
                cancellation = { writer.rowCount > 0 },
                pageSize = 2,
            )
        val result = export.export(ledgerId)
        assertEquals(ReadableLedgerExportResult.Cancelled, result)
        assertEquals(0, writer.commitCount, "a cancelled walk never commits (V-4)")
        assertTrue(writer.closeCount >= 1)
    }

    @Test
    fun aRuntimeNotReadyLeaseRejectsBeforeAnyReadOrWrite() {
        val harness = ExportHarness.build(rows = listOf(expenseRow("tx-a", Instant.parse("2026-03-02T02:00:00Z"))), pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        harness.leasePort.nextOutcome = LedgerRuntimeLeasePortOutcome.NotReady
        val result = harness.export.export(ledgerId)
        assertEquals(
            ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.RUNTIME_NOT_READY),
            result,
        )
        assertEquals(0, harness.source.nextCallCount, "no page was requested")
        assertNull(harness.openedWriter, "no target was opened")
    }

    @Test
    fun aTotalReadFailureIsTheTypedReadFailed() {
        val rows = listOf(expenseRow("tx-a", Instant.parse("2026-03-02T02:00:00Z")))
        val harness = ExportHarness.build(rows = rows, pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        harness.source.failTotal = true
        val result = harness.export.export(ledgerId)
        assertEquals(
            ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.READ_FAILED),
            result,
        )
    }

    @Test
    fun aMidWalkPageReadFailureIsTheTypedReadFailed() {
        val rows = (1..6).map { index -> expenseRow("tx-$index", Instant.parse("2026-03-0${index + 1}T02:00:00Z")) }
        val harness = ExportHarness.build(rows = rows, pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        harness.source.failFromSecondCall = true
        val writer = harness.defaultWriter()
        val result = harness.export.export(ledgerId)
        assertEquals(
            ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.READ_FAILED),
            result,
        )
        assertEquals(0, writer.commitCount)
    }

    @Test
    fun theProgressListenerReceivesTheZeroBaselineAndThePerPageCounts() {
        val rows = (1..5).map { index -> expenseRow("tx-$index", Instant.parse("2026-03-0${index + 1}T02:00:00Z")) }
        val harness = ExportHarness.build(rows = rows, pageSize = 2, clock = clock, catalogProvider = { groupCatalog() })
        val progress = mutableListOf<Pair<Long, Int>>()
        val export =
            ReadableLedgerExport(
                leasePort = harness.leasePort,
                flowPage = harness.flowPage,
                creationEntries = null,
                catalogProvider = { groupCatalog() },
                writerProvider = { RecordingSheetWriter() },
                clock = clock,
                pageSize = 2,
                progressListener = { exported, total -> progress.add(exported to total) },
            )
        val result = export.export(ledgerId)
        assertIs<ReadableLedgerExportResult.Succeeded>(result)
        // 0 baseline, one call per page boundary, and the final exact total (D-E9).
        assertEquals(listOf(0L to 5, 2L to 5, 4L to 5, 5L to 5), progress)
    }

    @Test
    fun theSourceLabelsFollowTheImportOverManualPrecedence() {
        val rows =
            listOf(
                expenseRow("tx-both", Instant.parse("2026-03-02T02:00:00Z")),
                expenseRow("tx-manual", Instant.parse("2026-03-03T02:00:00Z")),
                expenseRow("tx-none", Instant.parse("2026-03-04T02:00:00Z")),
            )
        val catalog = groupCatalog()
        val harness =
            ExportHarness.build(
                rows = rows,
                pageSize = 2,
                clock = clock,
                creationEntries = FakeCreationEntryBatchReader(importIds = setOf("tx-both"), manualIds = setOf("tx-both", "tx-manual")),
                catalogProvider = { catalog },
            )
        val writer = harness.defaultWriter()
        val result = harness.export.export(ledgerId)
        assertIs<ReadableLedgerExportResult.Succeeded>(result)
        assertEquals("导入创建", writer.rows[0].sourceLabel)
        assertEquals("手工创建", writer.rows[1].sourceLabel)
        assertEquals("来源未标注", writer.rows[2].sourceLabel)
        assertEquals(1, writer.headerCount)
    }

    // ---------------------------------------------------------------- bounded memory (V-3)

    @Test
    fun theBoundedMemoryWalkRequestsEachPageExactlyOnceWithAtMostOnePageInFlight() {
        // 50k 行级 (V-3): the counters are the observable; no memory sampling involved. Distinct
        // whole-second instants keep the page key strictly ordered (no Instant arithmetic needed).
        val count = 50_000
        val rows =
            (1..count).map { index ->
                expenseRow("tx-$index", Instant.fromEpochMilliseconds(START_EPOCH_MILLIS + index * 1_000L))
            }
        val sink = RecordingSink()
        val writer = ReadableLedgerXlsxStreamWriter(sink)
        val harness = ExportHarness.build(rows = rows, pageSize = 5_000, clock = clock, catalogProvider = { groupCatalog() }, consumedRowsProvider = { writer.rowsWritten })
        val export =
            ReadableLedgerExport(
                leasePort = harness.leasePort,
                flowPage = harness.flowPage,
                creationEntries = null,
                catalogProvider = { groupCatalog() },
                writerProvider = { writer },
                clock = clock,
                pageSize = 5_000,
            )
        val result = export.export(ledgerId)
        val succeeded = assertIs<ReadableLedgerExportResult.Succeeded>(result)
        assertEquals(count.toLong(), succeeded.exportedRows)
        assertEquals(count, succeeded.totalRows)
        assertEquals(10, harness.source.nextCallCount, "50_000 / 5_000 = 10 pages, one request each")
        assertEquals(0, harness.source.inFlightViolations, "已交付未消费页 ≤ 1 (F-8 observable)")
        assertEquals(1, sink.commitCount)
        assertTrue(sink.buffer.size() > 0L)
    }

    @Test
    fun theColumnContractRejectsAPrecisionOutsideTheDomain() {
        assertFailsWith<IllegalArgumentException> {
            readableExportAmountText(1L, 19)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun runExport(
        rows: List<LedgerEntryRow>,
        catalog: LedgerCatalog? = null,
        pageSize: Int = 2,
    ): ExportOutcome {
        val sink = RecordingSink()
        val writer = ReadableLedgerXlsxStreamWriter(sink)
        val source = FakeFlowPageSource(rows, pageSize, consumedRowsProvider = { writer.rowsWritten })
        val leasePort = FakeLeasePort()
        val export =
            ReadableLedgerExport(
                leasePort = leasePort,
                flowPage = source.flowPage(),
                creationEntries = null,
                catalogProvider = { catalog },
                writerProvider = { writer },
                clock = clock,
                pageSize = pageSize,
            )
        val result = export.export(ledgerId)
        return ExportOutcome(result, sink)
    }

    private class ExportOutcome(
        val result: ReadableLedgerExportResult,
        val sink: RecordingSink,
    )

    private class ZipRecord(
        val name: String,
        val time: Long,
        val content: String,
    )

    private fun zipEntries(bytes: ByteArray): List<ZipRecord> {
        val records = mutableListOf<ZipRecord>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                val content = zip.readBytes().toString(Charsets.UTF_8)
                records.add(ZipRecord(entry.name, entry.time, content))
                zip.closeEntry()
            }
        }
        return records
    }

    private val cny = CurrencyUnit("CNY", 2)

    private fun expenseRow(
        transactionId: String,
        statisticsAt: Instant,
        occurredAt: Instant = statisticsAt,
        minorUnits: Long = 1_00L,
        note: String? = null,
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("v-$transactionId"),
            kind = TransactionKind.EXPENSE,
            occurredAt = occurredAt,
            statisticsAt = statisticsAt,
            note = note,
            postings =
                listOf(
                    Posting(PostingId("p0-$transactionId"), AccountId("acc-expense-food"), Money.ofMinor(minorUnits, CNY)),
                    Posting(PostingId("p1-$transactionId"), AccountId("acc-bank"), Money.ofMinor(-minorUnits, CNY)),
                ),
        )

    private fun legsRow(
        transactionId: String,
        kind: TransactionKind,
        occurredAtIso: String,
        statisticsAtIso: String,
        note: String?,
        vararg postings: Posting,
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("v-$transactionId"),
            kind = kind,
            occurredAt = Instant.parse(occurredAtIso),
            statisticsAt = Instant.parse(statisticsAtIso),
            note = note,
            postings = postings.toList(),
        )

    /** The catalog behind the presentation groups; `acc-ghost` has NO row (the placeholder vector). */
    private fun groupCatalog(): LedgerCatalog {
        val ledger = DomainLedgerId("ledger-readable-export")

        fun account(
            id: String,
            kind: AccountKind,
            name: String,
        ): Account =
            Account(
                id = AccountId(id),
                ledgerId = ledger,
                kind = kind,
                currency = cny,
                ownedByUser = true,
                realAccount = kind != AccountKind.EXPENSE && kind != AccountKind.INCOME && kind != AccountKind.EQUITY,
                name = name,
            )

        fun leafCategory(
            id: String,
            parent: String,
            postingAccount: String,
            name: String,
        ): Category =
            Category(
                id = CategoryId(id),
                ledgerId = ledger,
                parentId = CategoryId(parent),
                postingAccountId = AccountId(postingAccount),
                active = true,
                kind = CategoryKind.EXPENSE,
                name = name,
            )
        val accounts =
            listOf(
                account("acc-bank", AccountKind.ASSET, "招商银行"),
                account("acc-wallet", AccountKind.ASSET, "零钱"),
                account("acc-prepaid", AccountKind.ASSET, "预付资产"),
                account("acc-equity", AccountKind.EQUITY, "期初权益"),
                account("acc-expense-food", AccountKind.EXPENSE, "餐饮支出"),
                account("acc-expense-service", AccountKind.EXPENSE, "服务支出"),
                account("acc-income-salary", AccountKind.INCOME, "工资收入"),
                account("acc-fee", AccountKind.EXPENSE, "手续费"),
            )
        val categories =
            listOf(
                Category(CategoryId("cat-food-parent"), ledger, null, null, true, CategoryKind.EXPENSE, "餐饮"),
                leafCategory("cat-food-leaf", "cat-food-parent", "acc-expense-food", "早餐"),
                Category(CategoryId("cat-service-parent"), ledger, null, null, true, CategoryKind.EXPENSE, "服务"),
                leafCategory("cat-service-leaf", "cat-service-parent", "acc-expense-service", "服务费"),
                Category(CategoryId("cat-income-parent"), ledger, null, null, true, CategoryKind.INCOME, "收入"),
                Category(CategoryId("cat-salary-leaf"), ledger, CategoryId("cat-income-parent"), AccountId("acc-income-salary"), true, CategoryKind.INCOME, "工资"),
                Category(CategoryId("cat-fee"), ledger, null, AccountId("acc-fee"), true, CategoryKind.EXPENSE, "手续费"),
            )
        val created = LedgerCatalog.create(accounts, categories)
        assertIs<DomainResult.Success<LedgerCatalog>>(created)
        return created.value
    }
}
