package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ImportCandidateCommitPort
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateDecisionSnapshot
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.OrdinaryFlowFormalFactory
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.data.SqlDelightImportSpineStore
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.ui.ImportConfirmUseCaseSet
import com.unifiedledger.ui.UuidV7ImportCommitIdSource
import kotlinx.datetime.YearMonth
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P7-04.D desktop composition-root end-to-end vectors (D-146; spec sections 3.2.3/3.3.2/7
 * D02/D04/D06): the wired per-kind confirm surface over the real spine store. One synthetic
 * two-row CMB bill goes through the wired intake; the authorization-shaped per-item confirms
 * (the host's single clock sample + per-item requestIds + the latest row's content hash) create
 * the formal transactions and the P7-03 read model presents them with the 导入创建 reverse
 * lineage (D06) and a consistent monthly payload; a typed stale-hash rejection writes nothing
 * (D02/D04 零写入); an equivalent same-request replay returns the ORIGINAL receipt (已成功项恰好
 * 一次) and a divergent snapshot under the same requestId types the conflict (判冲突)； an
 * injected flaky commit port yields the Unknown path — the never-landed claim replays cleanly
 * on the SAME requestId (不换 ID) and then replays to the original receipt; and the reopen
 * vector reads the list as the authority (重开恢复： 已确认项 confirmed、未派发项保持
 * pending_confirmation 可再授权， D04).
 */
class DesktopImportBatchConfirmationCompositionRootTest {
    private val secureRandom = SecureRandom()

    private fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)

    private fun withGraph(block: (graph: DesktopLedgerGraph, driver: JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            block(buildLedgerGraph(driver, createSchema = true), driver)
        } finally {
            driver.close()
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** The frozen BP-01 container with TWO data rows (anonymous synthetic values). */
    private fun syntheticTwoRowCmbBill(): ByteArray {
        val comment = (0..5).map { "\"SYN-CMB-META-$it\"" }
        val header = "\"交易日期\",\"交易时间\",\"收入\",\"支出\",\"余额\",\"交易类型\",\"交易备注\""
        val dataRowOne =
            listOf("\t20260315", "\t10:11:12", "", "35.80", "1000.00", "网联协议支付", "\tSYN-CMB-VEC-REMARK-1")
                .joinToString(",") { "\"$it\"" }
        val dataRowTwo =
            listOf("\t20260315", "\t11:22:33", "", "12.34", "987.66", "网联协议支付", "\tSYN-CMB-VEC-REMARK-2")
                .joinToString(",") { "\"$it\"" }
        val lines = comment + listOf("\"\"") + listOf(header) + listOf(dataRowOne, dataRowTwo) + listOf("\"\"")
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    /** Runs the wired intake for the synthetic bill and returns the accepted candidate rows. */
    private fun intakeTwoRows(graph: DesktopLedgerGraph): List<com.unifiedledger.application.ImportReviewRow> {
        val session = graph.facade.importIntakeSessionFactory()!!
        val intake =
            graph.facade.importFileIntake!!.intake(
                ImportFileIntakeInput(
                    format = ImportFormatCapabilities.CMB_CSV.identifier,
                    platform = ImportPlatformKind.DESKTOP,
                    session = session,
                    bytes = syntheticTwoRowCmbBill(),
                ),
            )
        val accepted = assertIs<ImportFileIntakeOutcome.Accepted>(intake)
        assertEquals(2, accepted.newCandidateIds.size)
        val rows = assertIs<ImportReviewRowsResult.Rows>(graph.facade.queryImportReviewRows!!.query(graph.ledgerId)).rows
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.candidateStatus == "pending_confirmation" && it.candidateKind == "ordinary_flow" })
        return rows
    }

    /** The host's per-item confirm request shape: the row hash + the single authorization sample. */
    private fun ordinaryConfirmRequest(
        graph: DesktopLedgerGraph,
        requestId: String,
        row: com.unifiedledger.application.ImportReviewRow,
        confirmedAt: String,
        fields: ImportConfirmDecisionFields =
            ImportConfirmDecisionFields.OrdinaryFlow(
                categoryId = graph.categoryId,
                fundingAccountId = graph.paymentAccountId,
            ),
    ): ImportCandidateConfirmRequest =
        ImportCandidateConfirmRequest(
            identity = ImportRequestIdentity(graph.ledgerId, ImportRequestId(requestId)),
            candidateId = row.candidateId,
            expectedContentHash = row.contentHash,
            explicitConfirmedAt = confirmedAt,
            decisionFields = fields,
        )

    private fun mintRequestId(graph: DesktopLedgerGraph): String = graph.facade.importConfirmRequestIdSource!!()

    // ------------------------------------------------------------------ wired surface

    @Test
    fun theFacadeExposesTheWiredBatchConfirmationSurface() {
        withGraph { graph, _ ->
            val useCases = assertIs<ImportConfirmUseCaseSet>(graph.facade.importConfirmUseCases?.invoke())
            assertTrue(
                useCases.ordinaryFlow != null &&
                    useCases.transferFlow != null &&
                    useCases.creditExpense != null &&
                    useCases.creditRepayment != null &&
                    useCases.mixedPayment != null,
            )
            // The per-item requestId mint produces fresh ids (one per item, once per intent).
            assertEquals(4, (1..4).map { mintRequestId(graph) }.toSet().size)
        }
    }

    // ------------------------------------------------------------------ per-item confirm + read model + replay (D02/D04/D06)

    @Test
    fun authorizedPerItemConfirmsSurfaceThroughTheLedgerViewWithImportLineage() {
        withGraph { graph, _ ->
            val rows = intakeTwoRows(graph)
            val confirmedAt =
                graph.facade.ledgerClock
                    .now()
                    .toString()

            // Item 1 confirms atomically (claim-first) with the authorization sample.
            val first =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    graph.facade.importConfirmUseCases
                        ?.invoke()!!
                        .ordinaryFlow!!
                        .execute(ordinaryConfirmRequest(graph, mintRequestId(graph), rows[0], confirmedAt)),
                )
            val transactionId = first.receipt.transactionId!!

            // D06: the confirmed transaction is visible through the P7-03 read model with the
            // 导入创建 reverse lineage and the exact legs.
            val detail = assertIs<TransactionDetailResult.Success>(graph.facade.queryTransactionDetail!!.query(transactionId)).detail
            assertEquals(transactionId, detail.transactionId)
            assertEquals(com.unifiedledger.application.CreationEntry.IMPORT_CREATED, detail.creationEntry)
            assertEquals(com.unifiedledger.domain.TransactionKind.EXPENSE, detail.kind)
            assertEquals(2, detail.legs.size)

            // D06: the monthly payload for the occurrence month counts exactly the confirmed
            // transaction — the not-yet-confirmed candidate contributes nothing.
            val march = assertIs<MonthlyActivityResult.Success>(graph.facade.queryMonthlyActivity!!.query(YearMonth(2026, 3))).activity
            val cnyRow = march.currencies.single()
            assertEquals(3_580L, cnyRow.netExpenseMinorUnits)
            assertEquals(1, cnyRow.transactionCount)

            // D02/D04: item 2 with a stale hash is typed-rejected with zero writes (the row
            // stays pending; no second transaction exists).
            val stale = ordinaryConfirmRequest(graph, mintRequestId(graph), rows[1], confirmedAt).copy(expectedContentHash = "sha256:stale-synthetic")
            val rejected =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    graph.facade.importConfirmUseCases
                        ?.invoke()!!
                        .ordinaryFlow!!
                        .execute(stale),
                )
            assertEquals("SPINE_STALE_FINGERPRINT", rejected.diagnostic.code)
            val afterRows = assertIs<ImportReviewRowsResult.Rows>(graph.facade.queryImportReviewRows!!.query(graph.ledgerId)).rows
            assertEquals("confirmed", afterRows[0].candidateStatus)
            assertEquals("pending_confirmation", afterRows[1].candidateStatus)
            assertEquals(1, cnyRowCount(graph, YearMonth(2026, 3)))

            // D04: 已成功项恰好一次 — the equivalent same-request replay returns the ORIGINAL
            // receipt (NoChange), never a second transaction.
            val replay =
                assertIs<ImportCandidateDecisionResult.NoChange>(
                    graph.facade.importConfirmUseCases
                        ?.invoke()!!
                        .ordinaryFlow!!
                        .execute(ordinaryConfirmRequest(graph, first.receipt.requestId.value, rows[0], confirmedAt)),
                )
            assertEquals(first.receipt, replay.receipt)

            // Q10.2 判冲突： the SAME requestId with a divergent decision snapshot (a different
            // confirmation time) types the conflict (never a second write).
            val divergent = ordinaryConfirmRequest(graph, first.receipt.requestId.value, rows[0], "2026-09-14T09:99:99Z")
            val conflict =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    graph.facade.importConfirmUseCases
                        ?.invoke()!!
                        .ordinaryFlow!!
                        .execute(divergent),
                )
            assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", conflict.diagnostic.code)
        }
    }

    private fun cnyRowCount(
        graph: DesktopLedgerGraph,
        month: YearMonth,
    ): Int =
        assertIs<MonthlyActivityResult.Success>(graph.facade.queryMonthlyActivity!!.query(month))
            .activity.currencies
            .single()
            .transactionCount

    // ------------------------------------------------------------------ Unknown simulation + replay (Q10.2)

    /** A flaky port that throws once (the handover fails, the claim rolls back) then delegates. */
    private class FlakyOnceConfirmCommitPort(
        private val delegate: ImportCandidateCommitPort,
    ) : ImportCandidateCommitPort {
        var failNext = true

        override fun commitOnce(
            identity: ImportRequestIdentity,
            snapshot: ImportCandidateDecisionSnapshot,
            allocateIds: () -> ImportCommitIds,
            catalog: LedgerCatalog,
            createFormalTransaction: (ImportCandidateFormalizationInput, ImportCommitIds) -> DomainResult<com.unifiedledger.application.ImportFormalCommit>,
        ): ImportCandidateDecisionResult =
            if (failNext) {
                failNext = false
                throw IllegalStateException("synthetic infrastructure failure after handover")
            } else {
                delegate.commitOnce(identity, snapshot, allocateIds, catalog, createFormalTransaction)
            }

        override fun commitRejectOnce(
            identity: ImportRequestIdentity,
            snapshot: ImportCandidateDecisionSnapshot,
            allocateStatusId: () -> ImportStatusHistoryId,
        ): ImportCandidateDecisionResult = delegate.commitRejectOnce(identity, snapshot, allocateStatusId)
    }

    @Test
    fun anUnknownHandoverReplaysCleanlyOnTheSameRequestIdAndThenReplaysToTheOriginalReceipt() {
        withGraph { graph, driver ->
            val rows = intakeTwoRows(graph)
            val confirmedAt =
                graph.facade.ledgerClock
                    .now()
                    .toString()
            val flaky =
                FlakyOnceConfirmCommitPort(
                    SqlDelightImportSpineStore(graph.database, driver),
                )
            val useCase =
                ConfirmImportCandidate(
                    commitPort = flaky,
                    idSource = UuidV7ImportCommitIdSource(UuidV7Generator(::secureRandomBytes), postingCount = 2),
                    createFormalTransaction = OrdinaryFlowFormalFactory(graph.facade.catalog),
                    catalog = graph.facade.catalog,
                )
            val request = ordinaryConfirmRequest(graph, "request-unknown-1", rows[0], confirmedAt)

            // The first attempt hands over and fails — the loop would record Unknown and pause.
            val thrown =
                runCatching { useCase.execute(request) }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException, "the synthetic handover failure must surface as the Unknown path")

            // 核对 = the equivalent replay with the SAME requestId (不换 ID)： the claim never
            // landed, so the replay wins the claim and performs the real confirm.
            val confirmed = assertIs<ImportCandidateDecisionResult.Accepted>(useCase.execute(request))
            // The replay of the landed request returns the ORIGINAL receipt (原 receipt 判成功).
            val original = assertIs<ImportCandidateDecisionResult.NoChange>(useCase.execute(request)).receipt
            assertEquals(confirmed.receipt, original)
            // The confirmed transaction is visible through the P7-03 read model.
            val detail = assertIs<TransactionDetailResult.Success>(graph.facade.queryTransactionDetail!!.query(confirmed.receipt.transactionId!!)).detail
            assertEquals(com.unifiedledger.application.CreationEntry.IMPORT_CREATED, detail.creationEntry)
        }
    }

    // ------------------------------------------------------------------ reopen recovery (R-Q09-3: 清单读即权威)

    @Test
    fun aReopenedLedgerReadsTheReviewListAsTheAuthorityAndThePendingItemsStayReAuthorizable() {
        val path: Path = Files.createTempFile("p704-import-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            val firstDriver = JdbcSqliteDriver(url)
            val firstGraph = buildLedgerGraph(firstDriver, createSchema = true)
            val rows = intakeTwoRows(firstGraph)
            val confirmedAt =
                firstGraph.facade.ledgerClock
                    .now()
                    .toString()

            // 派发中断模拟： item 1 confirmed, item 2 never dispatched (an abandoned batch leaves
            // its undispatched items pending — 义务③ / D04: 未派发项持久状态保持 pending).
            val first =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    firstGraph.facade.importConfirmUseCases
                        ?.invoke()!!
                        .ordinaryFlow!!
                        .execute(ordinaryConfirmRequest(firstGraph, "request-reopen-1", rows[0], confirmedAt)),
                )
            firstDriver.close()

            // 重开恢复： the rebuilt graph reads the list; the persisted per-candidate state IS
            // the authority — the confirmed item reads `confirmed`, the undispatched item reads
            // `pending_confirmation` (可再授权).
            val reopenedDriver = JdbcSqliteDriver(url)
            val reopenedGraph = buildLedgerGraph(reopenedDriver, createSchema = false)
            val reopenedRows = assertIs<ImportReviewRowsResult.Rows>(reopenedGraph.facade.queryImportReviewRows!!.query(reopenedGraph.ledgerId)).rows
            assertEquals(2, reopenedRows.size)
            assertEquals("confirmed", reopenedRows.first { it.candidateId == rows[0].candidateId }.candidateStatus)
            assertEquals("pending_confirmation", reopenedRows.first { it.candidateId == rows[1].candidateId }.candidateStatus)

            // The confirmed transaction still reads through the P7-03 read model after reopen.
            val detail =
                assertIs<TransactionDetailResult.Success>(reopenedGraph.facade.queryTransactionDetail!!.query(first.receipt.transactionId!!)).detail
            assertEquals(com.unifiedledger.application.CreationEntry.IMPORT_CREATED, detail.creationEntry)

            // 可再授权： a NEW intent (a fresh requestId and a fresh clock sample) confirms the
            // never-dispatched item on the reopened ledger.
            val reAuthorizedAt =
                reopenedGraph.facade.ledgerClock
                    .now()
                    .toString()
            val second =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    reopenedGraph.facade.importConfirmUseCases
                        ?.invoke()!!
                        .ordinaryFlow!!
                        .execute(
                            ordinaryConfirmRequest(reopenedGraph, "request-reopen-2", reopenedRows.first { it.candidateId == rows[1].candidateId }, reAuthorizedAt),
                        ),
                )
            val finalRows = assertIs<ImportReviewRowsResult.Rows>(reopenedGraph.facade.queryImportReviewRows!!.query(reopenedGraph.ledgerId)).rows
            assertTrue(finalRows.all { it.candidateStatus == "confirmed" })
            assertNotEquals(first.receipt.transactionId, second.receipt.transactionId)
            reopenedDriver.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
