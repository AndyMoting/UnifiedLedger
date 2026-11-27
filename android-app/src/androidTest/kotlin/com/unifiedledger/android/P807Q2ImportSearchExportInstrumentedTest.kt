package com.unifiedledger.android

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.export.readable.READABLE_EXPORT_HEADERS
import com.unifiedledger.application.export.readable.readableExportAmountText
import com.unifiedledger.application.import.wechat.BoundedXlsxReader
import com.unifiedledger.application.presentationTimes
import com.unifiedledger.application.statisticsPresentationTime
import com.unifiedledger.application.transactionKindBusinessName
import com.unifiedledger.domain.TransactionKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/**
 * P8-07 Q2 flow 1, the stage-acceptance design's first cross-slice flow (design section 2, row 1;
 * the original criteria are P8-04 D-10 / P8-02 / P8-05): IMPORT → SEARCH → EXPORT, on the dev
 * device, over the shared anonymous starting seed.
 *
 * WHAT THIS CASE IS. One device run of the whole flow, from the shared seed's independent initial
 * state (two confirmed transactions / four postings / one pending import candidate, the device
 * equivalent built by [P807Q2Flow1Seed]), through the real UI, with the formal ledger read before
 * and after every write leg through the app's own runtime accessor.
 *
 * THE STEPS AND WHAT EACH ONE PROVES
 * 1. Seed: the initial formal snapshot is read and asserted to be the shared seed's exact state
 *    (2 transactions, 4 postings, 1 pending candidate, the exact four account balances).
 * 2. Wrong-header import: the app is launched, the drawer's 导入账单 surface is opened, the
 *    self-entry format row's 选择文件 starts the REAL SAF picker, and the deliberately damaged
 *    XLSX (eight of the nine frozen header cells) is selected. The UI must report the BATCH-LEVEL
 *    rejection (parser diagnostic STRUCTURE_MISMATCH) and the formal snapshot must be UNCHANGED.
 * 3. Correct import + review confirmation: the correct one-row workbook (支出 / 7.77 CNY / 餐饮 >
 *    早餐 / 默认支付账户) is picked the same way; the intake must land exactly one new candidate;
 *    the candidate detail must show the prefilled decision as complete and the session match basis
 *    (分类 早餐 → 早餐, 资金账户 默认支付账户 → 默认支付账户); the checkbox + 进入批量确认 + the
 *    authorization page's 确认入账（1 项） must land exactly ONE transaction (3 transactions /
 *    6 postings, the exact balances, one confirmed candidate beside the still-pending seed one).
 * 4. Search by the confirmed DATE: leaving the import surface, the home search capsule opens the
 *    search surface; the custom window (2026-11-01T00:00:00Z .. 2026-11-03T00:00:00Z) that only
 *    the imported row falls in is typed into the two custom fields, and 提交搜索 must answer
 *    exactly one row. Opening it must show the ledger-consistent detail (发生/统计 time, 备注,
 *    类型, the exact signed amount and every leg line derived from the ledger's own detail read),
 *    and 返回 must land back on the same result surface (原路返回). A SECOND window
 *    (2026-10-01T00:00:00Z .. 2026-10-20T00:00:00Z) then brackets both seeded rows and excludes
 *    the imported one: its 共 2 笔 is the only observation that can prove the window's upper end is
 *    applied, because a surface that ignored it would also carry the November row (共 3 笔) while
 *    the first window's single row lies inside every plausible bound.
 * 5. Export the whole ledger: the drawer's 导出账目 surface's 开始导出 starts the REAL SAF
 *    CreateDocument picker; the platform save is confirmed; the app must report 已导出 3 笔. The
 *    committed file is read back and parsed with the PRODUCTION `BoundedXlsxReader`: the frozen
 *    ten-column header, exactly three data rows, the exact amount multiset
 *    {-12.34, 21.00, 7.77}, and the imported transaction's row equal to the cells derived from
 *    the exporter's own contract functions over the live catalog. The formal snapshot after the
 *    export must equal the snapshot after the confirmation (导出不改账本).
 *
 * THE UNPROVEN STEP (recorded here deliberately, never papered over). The system SAF CreateDocument
 * save dialog is the ONE step with no in-repo precedent: the P8-05 device chain replaces the picker
 * UI with a synthetic target, and the P8-02/P8-04 device legs only ever OPEN a document. This case
 * therefore drives the real picker with bounded waits and both the Chinese and English platform
 * labels (`保存` / `Save`, `下载` / `Downloads`, the roots drawer), and names the observed picker
 * windows when it cannot confirm the save. The SEED FILE and the flow's own artifacts are otherwise
 * untouched.
 *
 * WHY THIS CASE IS FIRST IN ITS SHARD. It seeds the app's OWN stable storage before any activity
 * launch, exactly like `ImportReviewPagingInstrumentedTest` publishes its library before the app
 * opens it. An earlier case that launched `MainActivity` would hold the ledger open, so the seed's
 * guard fails loudly instead of writing under a live handle. The ordinary inventory lists this
 * case first for that reason (see the manifest's header note).
 *
 * WHAT IS NOT CLAIMED: no duration; no real-Excel/WPS open (P8-05 R1's registered device item); no
 * stage acceptance (the design's section 6 keeps acceptance to its own decision).
 */
@RunWith(AndroidJUnit4::class)
class P807Q2ImportSearchExportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private var downloads: P807Q2Flow1Downloads? = null

    @After
    fun removeTheDownloadedFixtures() {
        downloads?.deleteAll()
        downloads = null
    }

    @Test
    fun theSharedSeedFlowsFromImportThroughSearchToTheExactExportRow() {
        val log: (String) -> Unit = { line -> Log.i(TAG, line) }
        val seeded = P807Q2Flow1Seed.prepare(targetContext, log)
        log("${P807Q2Flow1Facts.PREFIX} initial ${seeded.initial.logLine()}")

        val media =
            P807Q2Flow1Downloads(targetContext.contentResolver).also { downloads = it }
        media.insert(P807Q2Flow1Facts.WRONG_HEADER_FILE, P807Q2Flow1Facts.SELF_ENTRY_MIME, p807Q2Flow1WrongHeaderWorkbook())
        media.insert(P807Q2Flow1Facts.CORRECT_FILE, P807Q2Flow1Facts.SELF_ENTRY_MIME, p807Q2Flow1CorrectWorkbook())

        val ui = P807Q2Flow1Ui(instrumentation, log)
        val ledger = P807Q2Flow1Ledger(targetContext)

        // ---------------------------------------------------------------- launch + the seeded home
        ui.launch()
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the launched home must render the live overview's search capsule " +
                "(the seeded ledger opened); editable-less state is a failed launch",
            ui.awaitHomeCapsule(),
        )

        // ---------------------------------------------------------------- leg 1: wrong-header XLSX
        ui.openImport()
        assertTrue("${P807Q2Flow1Facts.PREFIX} the self-entry format row's 选择文件 must be reachable", ui.pickSelfEntryFormat())
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the wrong-header XLSX must be selectable in the real system picker",
            ui.pickImportFile(P807Q2Flow1Facts.WRONG_HEADER_FILE),
        )
        ui.awaitAppText(P807Q2Flow1Facts.INTAKE_RECENT_PREFIX + P807Q2Flow1Facts.WRONG_HEADER_FILE)
        ui.awaitAppText(P807Q2Flow1Facts.INTAKE_BATCH_REJECTED)
        val afterRejection = ledger.snapshot()
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} a batch-level rejection must leave the formal ledger unchanged",
            seeded.initial,
            afterRejection,
        )
        log("${P807Q2Flow1Facts.PREFIX} rejection ${afterRejection.logLine()}")

        // ---------------------------------------------------------------- leg 2: correct file + confirmation
        ui.backToImportTop()
        assertTrue("${P807Q2Flow1Facts.PREFIX} the self-entry format row must remain reachable after the rejection", ui.pickSelfEntryFormat())
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the correct XLSX must be selectable in the real system picker",
            ui.pickImportFile(P807Q2Flow1Facts.CORRECT_FILE),
        )
        ui.awaitAppText(P807Q2Flow1Facts.INTAKE_RECENT_PREFIX + P807Q2Flow1Facts.CORRECT_FILE)
        ui.awaitAppText(P807Q2Flow1Facts.INTAKE_ACCEPTED_LINE)
        ui.seekApp(candidateAmountText(P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR))
        val candidateRow =
            ui.findAppNode { node -> ui.textOf(node) == candidateAmountText(P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR) }
        assertNotNull(
            "${P807Q2Flow1Facts.PREFIX} the imported candidate row must render its exact amount",
            candidateRow,
        )
        ui.tap(candidateRow!!)
        ui.awaitAppText(P807Q2Flow1Facts.CANDIDATE_DETAIL_TITLE)
        // The self-entry row names its category by the full two-level path, so the basis resolves
        // through the D-024 path disambiguation (method token "path" → 路径消歧).
        ui.seekApp(P807Q2Flow1Facts.CANDIDATE_MATCH_BASIS_CATEGORY)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the detail must show the confirmed category's match basis",
            ui.hasAppText(P807Q2Flow1Facts.CANDIDATE_MATCH_BASIS_CATEGORY),
        )
        ui.seekApp(P807Q2Flow1Facts.CANDIDATE_MATCH_BASIS_ACCOUNT)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the detail must show the confirmed funding account's match basis",
            ui.hasAppText(P807Q2Flow1Facts.CANDIDATE_MATCH_BASIS_ACCOUNT),
        )
        ui.seekApp(P807Q2Flow1Facts.CANDIDATE_DECISION_COMPLETE)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the prefilled self-entry decision must be complete",
            ui.hasAppText(P807Q2Flow1Facts.CANDIDATE_DECISION_COMPLETE),
        )
        // The detail in front of us must be the IMPORTED row, not the seed candidate: 7.77 CNY and
        // the ordinary_flow kind are the imported self-entry row's own facts (the seed candidate is
        // a 12.34 transfer_flow). The selection toggle is anchored on this detail's own row, so a
        // click can never reach the list's first selectable row behind it.
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the opened detail must be the imported 7.77 CNY candidate",
            ui.hasAppText(candidateAmountText(P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR)),
        )
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the opened detail must carry the imported row's ordinary_flow kind",
            ui.hasAppText(P807Q2Flow1Facts.CANDIDATE_IMPORTED_META_PREFIX),
        )
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the imported candidate's own ${P807Q2Flow1Facts.CANDIDATE_CHECKBOX_DESCRIPTION} must establish the batch selection",
            ui.selectImportedCandidateForBatch(),
        )
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the selected candidate's batch entry must be clickable",
            ui.enterBatchConfirmation(),
        )
        ui.awaitAppText(P807Q2Flow1Facts.BATCH_CONFIRM_TITLE)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the confirmation page must carry the selected candidate",
            !ui.hasAppText("尚未勾选任何候选。"),
        )
        // The authorization page lists exactly the selected item: the imported row's amount, and
        // never the seed candidate's 12.34.
        assertNotNull(
            "${P807Q2Flow1Facts.PREFIX} the authorization page must list the imported 7.77 CNY candidate",
            ui.findAppNode { node -> ui.textOf(node).contains(candidateAmountText(P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR)) },
        )
        assertNull(
            "${P807Q2Flow1Facts.PREFIX} the authorization page must not carry the seed candidate",
            ui.findAppNode { node -> ui.textOf(node).contains(candidateAmountText(P807Q2Flow1Facts.SEED_CANDIDATE_AMOUNT_MINOR)) },
        )
        ui.seekApp(P807Q2Flow1Facts.BATCH_AUTHORIZE_BUTTON)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the authorization page must afford the single-item confirmation",
            ui.hasAppText(P807Q2Flow1Facts.BATCH_AUTHORIZE_BUTTON),
        )
        ui.clickApp(P807Q2Flow1Facts.BATCH_AUTHORIZE_BUTTON)
        // The design gate comes FIRST: the formal ledger gains exactly one transaction. The UI
        // summary is recorded after the ledger read-back (it is evidence, not the gate: the summary
        // block renders below the review rows, on the surface the app returns to).
        val afterConfirmation = awaitConfirmationApplied(ledger)
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the confirmation must leave exactly 3 transactions",
            3,
            afterConfirmation.transactionTotal,
        )
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the confirmation must leave exactly 6 postings",
            6,
            afterConfirmation.legs.size,
        )
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} exactly one new transaction may land",
            listOf("confirmed", "pending_confirmation"),
            afterConfirmation.candidateStatuses(),
        )
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the seed's own import candidate must stay pending — the confirmed row is the imported one",
            afterConfirmation.candidates.any { row -> row.startsWith("${P807Q2Flow1Facts.SEEDED_CANDIDATE_ID}|pending_confirmation") },
        )
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the confirmed balances must be the seeded amounts plus the imported row",
            p807Q2Flow1Balances(
                DEFAULT_MANAGEABLE_ACCOUNT_ID to -(P807Q2Flow1Facts.TRANSFER_MINOR + P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR),
                seeded.reconcileAccountId.value to P807Q2Flow1Facts.TRANSFER_MINOR,
                seeded.refundAccountId.value to -P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
                DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID to (P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR + P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR),
            ),
            afterConfirmation.balances,
        )
        // UI evidence for the SAME fact, recorded but not a render-position gate: the resolved
        // group must carry the imported candidate — its unique 7.77 CNY row and the ordinary_flow
        // kind (the seed candidate is a 12.34 transfer_flow). The authorize does not reliably
        // return the client to the review surface (the failing run still showed the batch page
        // after the ledger had committed), so the return is explicit before any scrolling.
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the batch surface must return to the import review surface",
            ui.returnToReviewSurface(),
        )
        ui.scrollBackTo(P807Q2Flow1Facts.RESOLVED_GROUP_ONE)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} exactly one candidate may sit in the resolved group",
            ui.hasAppText(P807Q2Flow1Facts.RESOLVED_GROUP_ONE),
        )
        ui.seekApp(candidateAmountText(P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR))
        val resolvedRow =
            ui.findAppNode { node -> ui.textOf(node) == candidateAmountText(P807Q2Flow1Facts.IMPORTED_AMOUNT_MINOR) }
        assertNotNull(
            "${P807Q2Flow1Facts.PREFIX} the resolved list must render the imported candidate's 7.77 CNY row",
            resolvedRow,
        )
        ui.seekApp(P807Q2Flow1Facts.CANDIDATE_IMPORTED_META_PREFIX)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the resolved row must carry the imported row's ordinary_flow kind",
            ui.hasAppText(P807Q2Flow1Facts.CANDIDATE_IMPORTED_META_PREFIX),
        )
        log(
            "${P807Q2Flow1Facts.PREFIX} resolved row text=${ui.textOf(resolvedRow!!)} " +
                "kind=${P807Q2Flow1Facts.CANDIDATE_IMPORTED_META_PREFIX} group=${P807Q2Flow1Facts.RESOLVED_GROUP_ONE}",
        )
        // The batch summary is OBSERVED, never asserted: find it when the surface shows it and log
        // the whole line; a miss names the surface instead of failing the case.
        val summary =
            runCatching {
                ui.seekApp(P807Q2Flow1Facts.BATCH_RESULT_PREFIX)
                ui.findAppNode { node -> ui.textOf(node).startsWith(P807Q2Flow1Facts.BATCH_RESULT_PREFIX) }
            }.getOrNull()
        if (summary != null) {
            log("${P807Q2Flow1Facts.PREFIX} batch summary=${ui.textOf(summary)}")
        } else {
            log("${P807Q2Flow1Facts.PREFIX} batch summary not on this surface; surface=${ui.surfaceTitle()}")
        }
        val detail =
            ledger.read { facade -> p807Q2Flow1AddedTransaction(facade, seeded.knownTransactionIds) }
        assertEquals("${P807Q2Flow1Facts.PREFIX} the imported row must land as an EXPENSE", TransactionKind.EXPENSE, detail.kind)
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the imported row's occurred-at must be the file's own time",
            Instant.parse(P807Q2Flow1Facts.IMPORTED_OCCURRED_AT_INSTANT),
            detail.occurredAt,
        )
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the self-entry import confirm persists the domain's frozen empty-string note " +
                "(AssetPaidOrdinaryExpenseCommand.note defaults to \"\", and OrdinaryFlowFormalFactory passes none)",
            "",
            detail.note,
        )
        assertEquals("${P807Q2Flow1Facts.PREFIX} the imported expense carries exactly two legs", 2, detail.legs.size)
        val expected = ledger.read { facade -> p807Q2Flow1ExpectedExportRow(facade, detail) }
        log("${P807Q2Flow1Facts.PREFIX} confirmed ${afterConfirmation.logLine()} ${expected.logLine()}")

        // ---------------------------------------------------------------- leg 3: confirmed-date search
        ui.backToHome()
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} leaving the import surface must return to the home overview",
            ui.awaitHomeCapsule(),
        )
        ui.clickApp(P807Q2Flow1Facts.HOME_SEARCH_CAPSULE)
        ui.awaitAppText(P807Q2Flow1Facts.SEARCH_WINDOW_HEADER)
        ui.clickApp(P807Q2Flow1Facts.SEARCH_WINDOW_CUSTOM)
        ui.seekApp(P807Q2Flow1Facts.SEARCH_CUSTOM_END_LABEL)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the custom window must expose both bound fields",
            ui.editableFieldCount() >= 2,
        )
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the custom window's lower bound must be typeable",
            ui.setFieldText(2, P807Q2Flow1Facts.SEARCH_WINDOW_START),
        )
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the custom window's upper bound must be typeable",
            ui.setFieldText(1, P807Q2Flow1Facts.SEARCH_WINDOW_END),
        )
        // The submit's own effect criterion is the result surface, with one logged retry (see
        // P807Q2Flow1Ui.submitSearch): the keyboard is dismissed by the window list, the button is
        // clicked only while it self-reports enabled and visible, and a miss never becomes an
        // unbounded wait.
        ui.submitSearch()
        val resultRow =
            ui.findAppNode { node -> ui.textOf(node).startsWith("${transactionKindBusinessName(detail.kind)} · 统计 ") }
        assertNotNull("${P807Q2Flow1Facts.PREFIX} the single search result row must render", resultRow)
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the result row must carry the confirmed transaction's statistics time and note",
            // The shared flow row's ANDROID copy (flowRowHeaderText): Chinese business name · 统计 ·
            // 上海墙钟 · note. A note that is blank is not rendered, so the confirmed self-entry
            // row's frozen empty-string note adds no " · " separator.
            searchRowText(detail),
            ui.textOf(resultRow!!),
        )
        ui.tap(resultRow!!)
        ui.awaitAppText(P807Q2Flow1Facts.TRANSACTION_DETAIL_TITLE)
        ui.seekApp(transactionHeadline(detail))
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the detail headline must be the exact signed amount",
            ui.hasAppText(transactionHeadline(detail)),
        )
        // The ANDROID detail card's own four lines (`cTransactionDetailUserLines`, D-369 C): the
        // role-labelled `Asia/Shanghai` wall clock derived from this detail's raw stored instants —
        // never the bare UTC instant, which the card no longer prints — the frozen 无 for the
        // imported row's blank note, and the Chinese business name.
        val times = presentationTimes(detail.occurredAt, detail.statisticsAt)
        sequenceOf(
            "${times.occurred.role.label}：${times.occurred.text()}",
            "${times.statistics.role.label}：${times.statistics.text()}",
            "备注：${detail.note?.takeIf { note -> note.isNotBlank() } ?: "无"}",
            "类型：${transactionKindBusinessName(detail.kind)}",
        ).forEach { line ->
            ui.seekApp(line)
            assertTrue("${P807Q2Flow1Facts.PREFIX} the detail must show the ledger's own line [$line]", ui.hasAppText(line))
        }
        detail.legs.forEach { leg ->
            val line = detailLegLine(leg)
            ui.seekApp(line)
            assertTrue("${P807Q2Flow1Facts.PREFIX} the detail must show the exact leg [$line]", ui.hasAppText(line))
        }
        ui.scrollBackTo(P807Q2Flow1Facts.TRANSACTION_DETAIL_TITLE)
        ui.clickApp(P807Q2Flow1Facts.BACK_LABEL)
        ui.awaitAppText(P807Q2Flow1Facts.SEARCH_RESULTS_ONE)
        log("${P807Q2Flow1Facts.PREFIX} search rows=1 detail=${detail.transactionId.value}")

        // ---- the UPPER bound must be OBSERVED, not inferred. The first window's single row lies
        // inside every plausible bound, so a surface that ignored the upper end would answer 共 1 笔
        // all the same. This second window brackets both seeded October rows and excludes the
        // imported November row, so only an applied upper end can answer 共 2 笔 (ignoring it would
        // answer 共 3 笔); the seed row is matched by the ledger's OWN row copy and the imported row
        // by the same text prefix as a bounded negative assertion. The detail return restores the
        // TAP-TIME scroll position, so the condition form (both custom fields) can sit outside the
        // LazyColumn's composed window: the form is scrolled back into the viewport and its presence
        // is asserted BEFORE anything is typed into it, so a missing field fails with that cause
        // instead of a silent setFieldText false.
        ui.scrollBackTo(P807Q2Flow1Facts.SEARCH_WINDOW_HEADER)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the seed window's form must be back in the viewport",
            ui.editableFieldCount() >= 2,
        )
        val seedExpense =
            ledger.read { facade -> p807Q2Flow1TransactionDetail(facade, P807Q2Flow1Facts.REFUNDABLE_EXPENSE_TRANSACTION_ID) }
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the seed window's lower bound must be typeable",
            ui.setFieldText(2, P807Q2Flow1Facts.SEARCH_SEED_WINDOW_START),
        )
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the seed window's upper bound must be typeable",
            ui.setFieldText(1, P807Q2Flow1Facts.SEARCH_SEED_WINDOW_END),
        )
        ui.submitSearch(P807Q2Flow1Facts.SEARCH_RESULTS_TWO)
        val seedRowText = searchRowText(seedExpense)
        ui.seekApp(seedRowText)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the seed window must carry the seeded expense row [$seedRowText]",
            ui.hasAppText(seedRowText),
        )
        assertNull(
            "${P807Q2Flow1Facts.PREFIX} the seed window's exclusive upper bound must exclude the imported November row",
            ui.awaitNoAppText(searchRowText(detail)),
        )
        log(
            "${P807Q2Flow1Facts.PREFIX} search rows=2 seed=${seedExpense.transactionId.value} " +
                "row=[$seedRowText] excluded=[${searchRowText(detail)}]",
        )

        ui.backToHome()
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} closing the search surface must return to the home overview",
            ui.awaitHomeCapsule(),
        )

        // ---------------------------------------------------------------- leg 4: full-ledger export
        ui.openDrawerRow(P807Q2Flow1Facts.DRAWER_EXPORT_ROW)
        ui.awaitAppText(P807Q2Flow1Facts.EXPORT_START_BUTTON)
        // Freshness before the write: the byte read must return THIS run's workbook, so every
        // earlier export file is removed before the picker creates the new one. The read channel is
        // find/cat argv, never a shell (see P807Q2Flow1Command), so the name glob is find's own.
        p807Q2Flow1ClearExportFiles(instrumentation.uiAutomation, log)
        ui.clickApp(P807Q2Flow1Facts.EXPORT_START_BUTTON)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the real system CreateDocument picker must accept the save",
            ui.saveExportFile(),
        )
        ui.awaitAppText(P807Q2Flow1Facts.EXPORT_SUCCESS_PREFIX_3)
        val exportedBytes = p807Q2Flow1NewestExportFile(instrumentation.uiAutomation, targetContext.contentResolver, log)
        assertExportedWorkbook(exportedBytes, expected, detail)
        val afterExport = ledger.snapshot()
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the export must not change the ledger",
            afterConfirmation,
            afterExport,
        )
        ui.clickApp(P807Q2Flow1Facts.BACK_LABEL)
        assertTrue(
            "${P807Q2Flow1Facts.PREFIX} the export surface must return to the home overview",
            ui.awaitHomeCapsule(),
        )
        log(
            "${P807Q2Flow1Facts.PREFIX} PASS transactions=${afterExport.transactionTotal} postings=${afterExport.legs.size} " +
                "exportAmounts=[${EXPORTED_AMOUNTS.joinToString(",")}] imported=${detail.transactionId.value} " +
                "amount=${expected.amountText} ${CATALOG_MANAGED_CURRENCY.code}",
        )
    }

    // ------------------------------------------------------------------ export workbook assertions

    private fun assertExportedWorkbook(
        bytes: ByteArray,
        expected: P807ExpectedExportRow,
        detail: TransactionDetail,
    ) {
        val workbook = BoundedXlsxReader.read(bytes)
        assertEquals("${P807Q2Flow1Facts.PREFIX} the export resolves exactly one sheet", 1, workbook.numberOfSheets)
        val sheet = workbook.firstSheet
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the export carries the header row plus every ledger transaction",
            EXPORTED_ROWS + 1,
            sheet.lastRowNum + 1,
        )
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the header row carries the frozen ten columns",
            READABLE_EXPORT_HEADERS.size,
            sheet.getRow(0)!!.lastCellNum,
        )
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the header row is the frozen column contract",
            READABLE_EXPORT_HEADERS,
            rowTexts(sheet, 0),
        )
        val dataRows = (1..EXPORTED_ROWS).map { index -> rowTexts(sheet, index) }
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the export must carry the exact seeded and imported amounts",
            EXPORTED_AMOUNTS.sorted(),
            dataRows.map { row -> row[3] }.sorted(),
        )
        val matches =
            dataRows.filter { row ->
                row[3] == expected.amountText &&
                    row[2] == expected.kindLabel &&
                    row[5] == expected.account1Name &&
                    row[6] == expected.account2Name
            }
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} exactly one exported row may match the confirmed transaction: expected=${expected.logLine()} " +
                "rows=${dataRows.map { row -> row.joinToString("|") }}",
            1,
            matches.size,
        )
        val row = matches.single()
        assertEquals(
            "${P807Q2Flow1Facts.PREFIX} the imported transaction's exported row must equal the exporter's own A..J cells " +
                "(the ledger's note face and the J source label included)",
            expected.rowCells(detail.note.orEmpty()),
            row,
        )
        Log.i(TAG, "${P807Q2Flow1Facts.PREFIX} export rows=$EXPORTED_ROWS row=${row.joinToString("|")}")
    }

    // ------------------------------------------------------------------ display mirrors (app-ui internal copy)

    private fun candidateAmountText(minorUnits: Long): String = readableExportAmountText(minorUnits, CATALOG_MANAGED_CURRENCY.precision) + " " + CATALOG_MANAGED_CURRENCY.code

    /**
     * The product's own result-row copy for [detail] (`flowRowHeaderText`, the shared search row
     * under the Android profile): Chinese business name · 统计 · 上海本地墙钟 · a non-blank note.
     */
    private fun searchRowText(detail: TransactionDetail): String {
        val time = statisticsPresentationTime(detail.statisticsAt).text()
        val note = detail.note?.takeIf { it.isNotBlank() }
        return transactionKindBusinessName(detail.kind) + " · 统计 " + time + (note?.let { " · $it" } ?: "")
    }

    /** `transactionDetailHeadlineAmount` for a two-leg EXPENSE (the exact signed amount headline). */
    private fun transactionHeadline(detail: TransactionDetail): String {
        val magnitude = detail.legs.maxOf { leg -> kotlin.math.abs(leg.amount.minorUnits) }
        return "-" + readableExportAmountText(magnitude, CATALOG_MANAGED_CURRENCY.precision) + " " + CATALOG_MANAGED_CURRENCY.code
    }

    /** `transactionDetailLegContentDescription`'s exact mirror, built from the ledger's own leg read. */
    private fun detailLegLine(leg: TransactionDetailLeg): String =
        leg.accountName + " " +
            readableExportAmountText(leg.amount.minorUnits, leg.amount.currency.precision) + " " +
            leg.amount.currency.code +
            (leg.categoryName?.let { name -> "（分类：$name）" } ?: "（无分类）")

    /**
     * The design gate: the formal ledger gains exactly one transaction. Polls the live ledger
     * (bounded) until the new transaction count and posting count land, so the authorize's async
     * per-item dispatch is not raced; a miss returns the last snapshot for the loud asserts.
     */
    private fun awaitConfirmationApplied(
        ledger: P807Q2Flow1Ledger,
        timeoutMillis: Long = CONFIRMATION_TIMEOUT_MILLIS,
    ): P807FormalSnapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var snapshot = runCatching { ledger.snapshot() }.getOrNull()
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = snapshot
            if (current != null && current.transactionTotal == EXPORTED_ROWS && current.legs.size == EXPORTED_POSTINGS) return current
            SystemClock.sleep(500)
            snapshot = runCatching { ledger.snapshot() }.getOrNull() ?: snapshot
        }
        return snapshot ?: ledger.snapshot()
    }

    /** One row's cell texts, positionally resolved (numeric cells carry their text in `rawValue`). */
    private fun rowTexts(
        sheet: BoundedXlsxReader.Sheet,
        rowIndex: Int,
    ): List<String> =
        (0 until READABLE_EXPORT_HEADERS.size).map { column ->
            sheet
                .getRow(rowIndex)!!
                .getCell(column)
                ?.let { cell -> cell.string ?: cell.rawValue }
                .orEmpty()
        }

    private companion object {
        const val TAG = "UnifiedLedger"

        /** The initial seed's two transactions plus the flow's one confirmed import. */
        const val EXPORTED_ROWS = 3

        /** The same two transactions' four postings plus the imported expense's two legs. */
        const val EXPORTED_POSTINGS = 6

        /** The ledger poll's bound: the authorize dispatches each item asynchronously. */
        const val CONFIRMATION_TIMEOUT_MILLIS = 120_000L

        /**
         * The exact exported amount column of the shared seed plus this flow's row: the transfer's
         * outflow leg (-12.34), the refundable expense (+21.00) and the imported expense (+7.77),
         * as the frozen signed decimal text.
         */
        val EXPORTED_AMOUNTS = listOf("-12.34", "21.00", "7.77")
    }
}
