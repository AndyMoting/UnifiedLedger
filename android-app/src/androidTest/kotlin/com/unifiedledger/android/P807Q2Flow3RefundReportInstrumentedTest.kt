package com.unifiedledger.android

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ManualRefundCandidate
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806CurrencyView
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P8-07 Q2 flow 3, the stage-acceptance design's third cross-slice flow
 * (`docs/P807_DESIGN_GATE.local.md` «已批准的跨切片流程» row 3): MANUAL REFUND -> CROSS-MONTH
 * REPORT -> DAY DETAIL, on the dev device, over the shared anonymous starting seed.
 *
 * WHAT THIS CASE IS. One device run of the whole flow, from the shared seed's independent initial
 * state (two confirmed transactions / four postings / one pending import candidate, the device
 * equivalent built by [P807Q2Flow1Seed]), through the real UI: the over-cap refund is refused with
 * the frozen sentence and zero formal writes, the legal full-cap refund creates exactly one
 * REFUND_RECEIPT at the pinned arrival instant, the original expense's month is unchanged while the
 * arrival month carries the same refund, and the calendar's day cell opens a day detail that the
 * read-only transaction detail round-trips back to. The formal ledger is read before and after
 * every write leg through the app's own runtime accessor, and the report, calendar, drilldown and
 * refund-candidate faces are read through the same runtime's leased ports, so the UI copy and the
 * authoritative payload are compared rather than trusted.
 *
 * THE LEGS AND WHAT EACH ONE PROVES
 * 1. Initial state: the seed's own read, the exact 2/4/1 counts, the exact four balances, the one
 *    refundable candidate at its full structural cap, the October report face, and the arrival
 *    month's calendar + day-detail faces BEFORE any interaction (the zero-write oracle).
 * 2. The refund form: the record-plus opens the expense draft, the 退款到账 type row retitles the
 *    surface, the candidate page lands the one row verbatim, and the field chain carries its four
 *    labels — with the amount label still naming no currency, because no arrival account is chosen.
 * 3. The over-cap refusal: the original row, the arrival account row, 99.99 and the pinned instant
 *    reach the confirmation page (the product refuses ON CONFIRM, not before), the confirm lands
 *    back on the refund surface with 退款金额超过原交易的支出金额, 继续 is gone, and the whole
 *    formal read — versions included — plus all four faces are unchanged.
 * 4. The legal refund: one field edit brings 继续 back, 21.00 is accepted, the confirmation page
 *    prints its four rows and the pinned time, the submit is activated AT MOST ONCE, and the ledger
 *    inside a bounded window carries exactly one new transaction — kind REFUND_RECEIPT, occurred-at
 *    and statistics-at both the pinned instant. The 支出已创建 notice is a logged signal only: it is
 *    a transient render, and device round 3 (run `45620555293`) showed a notice predicate calling a
 *    landed write "click-not-applied" and re-clicking a submit that had already committed.
 * 5. The ledger face: exactly one new transaction, kind REFUND_RECEIPT, occurred-at ==
 *    statistics-at == the pinned instant, two legs (+21.00 on the arrival account, -21.00 on the
 *    inherited expense account), the seeded transactions, postings, versions and candidates
 *    unchanged, and the four balances equal to the seeded ones plus the new legs' exact minor units.
 * 6. The candidate empty state: a fresh refund draft reads 暂无可退交易 (the full refund consumed the
 *    structural cap).
 * 7. The arrival month's report: both custom-window bounds read back from ONE tree BEFORE the commit,
 *    then the November window lands its exact period line, net expense -21.00, ordinary income 0.00,
 *    balance 21.00 and 普通收支笔数：1, each line EQUAL to the line the authoritative payload produces.
 * 8. The original expense's month: the October window's two bounds read back the same way, it still
 *    reads 净支出（含退款） 21.00 and 普通收支笔数：1, and its whole port face equals the pre-flow face
 *    item by item.
 * 9. The calendar and the day detail: one previous step — taken only once the month row reports a
 *    resolved YYYY-MM label and an ENABLED ‹, and verified on that label — pins the HOME cursor to the
 *    domain's first month 2026-10, the calendar opens there (its own 2026年10月 label is the proof),
 *    one forward step reaches 2026年11月, the arrival day's cell carries its exact accessibility text,
 *    and that cell opens 报表明细 with 共 1 笔明细, the day header, the row's own headline and each
 *    posting line.
 * 10. The detail round trip: the row's read-only detail opens, one 返回 reaches the same day detail
 *     and a second 返回 the same calendar month, and the whole read face equals the post-write read.
 *
 * NOT COVERED, DELIBERATELY (`manual_refund_receipt` row counts, the request-decided audit row and
 * the V-5 "draft/cancel leaves 0 rows" count have no product-level read face: `SubmitManualRefundReceipt`
 * exposes only a per-request lookup, and the spec gives those claims the JVM真库 evidence type,
 * `2026-11-12-p8-03-refund-entry-design.md` section 5 V-5). This case also never reads SQL and never
 * asserts a relation-table row count.
 *
 * WHAT IS NOT CLAIMED: no duration bound, no network, no force-stop/restart, no partial refund (the
 * spec's «不做» list), and no product code change — this case drives the copied product through its
 * own UI and never writes the ledger itself.
 */
@RunWith(AndroidJUnit4::class)
class P807Q2Flow3RefundReportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun theManualRefundLandsInItsArrivalMonthAndReadsBackThroughTheDayDetail() {
        val log: (String) -> Unit = { line -> Log.i(TAG, line) }
        val facts = P807Q2Flow3Facts
        val arrivalAccountName = P807Q2Flow1Facts.REFUND_ACCOUNT_NAME

        // ---------------------------------------------------------------- L1: the seeded initial state
        val seeded = P807Q2Flow1Seed.prepare(targetContext, log)
        val ledger = P807Q2Flow3Ledger(targetContext, log)
        val ui = P807Q2Flow3Ui(P807Q2Flow1Ui(instrumentation, log), instrumentation, log)
        ui.launch()
        assertTrue(
            "${facts.PREFIX} the launched home must render the live overview's search capsule (the seeded ledger opened)",
            ui.awaitHome(),
        )
        val s0 = ledger.snapshot()
        assertEquals("${facts.PREFIX} the launched read must equal the seed's own read of the shared state", seeded.initial, s0.formal)
        assertEquals("${facts.PREFIX} the shared seed must start with 2 transactions", 2, s0.formal.transactionTotal)
        assertEquals("${facts.PREFIX} the shared seed must start with 4 postings", 4, s0.formal.legs.size)
        assertEquals("${facts.PREFIX} the shared seed must start with one pending import candidate", 1, s0.formal.pendingCandidateCount())
        assertEquals(
            "${facts.PREFIX} the shared seed's balances must be the exact seeded amounts",
            p807Q2Flow1Balances(
                DEFAULT_MANAGEABLE_ACCOUNT_ID to -P807Q2Flow1Facts.TRANSFER_MINOR,
                seeded.reconcileAccountId.value to P807Q2Flow1Facts.TRANSFER_MINOR,
                seeded.refundAccountId.value to -P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
                DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID to P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            ),
            s0.formal.balances,
        )
        val candidatesBefore = loadedCandidates(ledger)
        assertEquals("${facts.PREFIX} the seed must offer exactly one refundable original", 1, candidatesBefore.size)
        val candidateBefore = candidatesBefore.single()
        assertEquals("${facts.PREFIX} the candidate row must be the seeded expense's exact line", facts.CANDIDATE_ROW, p807Q2Flow3CandidateRowText(candidateBefore))
        assertEquals(
            "${facts.PREFIX} the full structural cap must still be available",
            P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            candidateBefore.remaining.minorUnits,
        )
        val octoberBefore = ledger.report(facts.octoberPeriod())
        val octoberBeforeFace = p807Q2Flow3ReportLines(octoberBefore)
        assertSeededOctoberFace(octoberBefore)
        val arrivalCalendarBefore = ledger.calendar(NOVEMBER)
        val arrivalDayBefore = arrivalDayOf(arrivalCalendarBefore)
        val arrivalWindow = arrivalDayBefore.window
        val calendarBeforeFace = p807Q2Flow3CalendarLines(arrivalCalendarBefore)
        val drilldownBefore = ledger.drilldown(arrivalWindow)
        val drilldownBeforeFace = p807Q2Flow3DrilldownLines(drilldownBefore)
        assertEquals("${facts.PREFIX} the arrival day must contribute nothing before the refund", 0, arrivalDayBefore.transactionCount)
        assertEquals("${facts.PREFIX} the arrival day's detail must be empty before the refund", 0, drilldownBefore.totalCount)
        assertEquals("${facts.PREFIX} the arrival day's detail page must carry no row before the refund", 0, drilldownBefore.page.rows.size)
        log(
            "${facts.PREFIX} initial " + s0.logLine() + " october=[" + octoberBeforeFace.joinToString(" ¦ ") + "] " +
                "drilldown=[" + drilldownBeforeFace.joinToString(" ¦ ") + "]",
        )

        // ---------------------------------------------------------------- L2: the refund form
        ui.openExpenseEntry()
        assertTrue("${facts.PREFIX} the 退款到账 type row must switch the draft to a manual refund", ui.selectRefundType())
        assertTrue(
            "${facts.PREFIX} the candidate page must land the one refundable row verbatim",
            ui.awaitExactText(facts.CANDIDATE_ROW, timeoutMillis = CANDIDATE_TIMEOUT_MILLIS, seek = true),
        )
        sequenceOf(facts.LABEL_ORIGINAL, facts.LABEL_ARRIVAL, facts.LABEL_AMOUNT_UNSELECTED, facts.LABEL_OCCURRED_AT).forEach { label ->
            assertTrue("${facts.PREFIX} the refund field chain must carry the label [$label]", ui.awaitAnywhereText(label))
        }

        // ---------------------------------------------------------------- L3: the over-cap refusal writes nothing
        assertTrue("${facts.PREFIX} the candidate row's own radio must become the draft's original", ui.selectOriginalRow(facts.CANDIDATE_ROW))
        assertTrue(
            "${facts.PREFIX} the arrival row must make $arrivalAccountName the draft's destination",
            ui.selectArrivalAccount(arrivalAccountName),
        )
        assertTrue(
            "${facts.PREFIX} the amount label must follow the chosen arrival account [${facts.LABEL_AMOUNT_CNY}]",
            ui.awaitAnywhereText(facts.LABEL_AMOUNT_CNY),
        )
        assertTrue(
            "${facts.PREFIX} the amount field must accept [${facts.OVER_CAP_AMOUNT_TEXT}]",
            ui.setFieldByLabel(facts.LABEL_AMOUNT_CNY, facts.OVER_CAP_AMOUNT_TEXT),
        )
        assertTrue(
            "${facts.PREFIX} the occurred-at field must accept the pinned arrival instant",
            ui.setFieldByLabel(facts.LABEL_OCCURRED_AT, facts.ARRIVAL_INSTANT_TEXT),
        )
        assertTrue("${facts.PREFIX} the soft keyboard must be dismissed before the submit", ui.hideKeyboard())
        assertTrue(
            "${facts.PREFIX} the over-cap amount must still reach the confirmation page (the product refuses on confirm)",
            ui.clickVerifiedExact("proceedOverCap", facts.CONTINUE) { ui.hasExactText(facts.CONFIRM_TITLE) },
        )
        assertTrue(
            "${facts.PREFIX} the over-cap confirm must land back on the refund surface with the frozen sentence",
            ui.clickVerifiedExact("confirmOverCap", facts.CONFIRM_SUBMIT) { ui.hasAnywhereText(facts.CAP_REJECTION) },
        )
        assertTrue("${facts.PREFIX} the refusal must land on the refund edit surface", ui.hasAnywhereText(facts.ENTRY_TITLE_REFUND))
        assertNull("${facts.PREFIX} the refusal surface must not offer 继续 (state-carrying DomainRejected)", ui.awaitNoText(facts.CONTINUE))
        val s1 = ledger.snapshot()
        assertEquals("${facts.PREFIX} the refusal must write nothing: the whole formal read must equal the seed read", s0, s1)
        val candidateAfterRefusal = loadedCandidates(ledger).singleOrNull()
        checkNotNull(candidateAfterRefusal) { "${facts.PREFIX} the refusal must leave the one refundable candidate" }
        assertEquals(
            "${facts.PREFIX} the refusal must not consume any cap",
            facts.CANDIDATE_ROW,
            p807Q2Flow3CandidateRowText(candidateAfterRefusal),
        )
        assertEquals(
            "${facts.PREFIX} the refusal must leave the October report face unchanged",
            octoberBeforeFace,
            p807Q2Flow3ReportLines(ledger.report(facts.octoberPeriod())),
        )
        assertEquals(
            "${facts.PREFIX} the refusal must leave the arrival month's calendar face unchanged",
            calendarBeforeFace,
            p807Q2Flow3CalendarLines(ledger.calendar(NOVEMBER)),
        )
        assertEquals(
            "${facts.PREFIX} the refusal must leave the arrival day's detail face unchanged",
            drilldownBeforeFace,
            p807Q2Flow3DrilldownLines(ledger.drilldown(arrivalWindow)),
        )

        // ---------------------------------------------------------------- L4: the legal full-cap refund
        assertTrue(
            "${facts.PREFIX} one field edit must bring 继续 back",
            ui.setFieldByLabel(facts.LABEL_AMOUNT_CNY, facts.LEGAL_AMOUNT_TEXT),
        )
        // Task book section 5.3: the bottom action is reached with the soft keyboard dismissed. The
        // field input above hides it BEFORE its own typing only, so the IME it opened can still sit
        // over 继续 here.
        ui.hideKeyboard()
        assertTrue(
            "${facts.PREFIX} the full-cap amount must be accepted and open the confirmation page",
            ui.clickVerifiedExact("proceedLegal", facts.CONTINUE) { ui.hasExactText(facts.CONFIRM_TITLE) },
        )
        sequenceOf(
            facts.CONFIRM_ORIGINAL_ROW,
            facts.confirmArrivalRow(arrivalAccountName),
            facts.CONFIRM_CATEGORY_ROW,
            facts.CONFIRM_AMOUNT_ROW,
            facts.confirmTimeRow(facts.ARRIVAL_INSTANT),
        ).forEach { line ->
            assertTrue(
                "${facts.PREFIX} the confirmation page must print the exact row [$line]",
                ui.awaitAnywhereText(line, seek = false),
            )
        }
        // THE SUBMIT IS ACTIVATED AT MOST ONCE, and the judgement is the LEDGER, never the notice.
        // Device round 3 (run `45620555293`) had the write already committed while the criterion
        // watched 支出已创建: three rounds probed a transient render, each reported
        // click-not-applied, and the retries re-clicked a submit that had written. So the action is
        // sent once ([P807Q2Flow3Ui.activateOnce] proves the target was on screen and the action was
        // accepted), the notice is only logged, and the leg passes on the new ledger transaction.
        val submitSent = ui.activateOnce("confirmLegal", facts.CONFIRM_SUBMIT)
        if (!submitSent) {
            throw AssertionError(
                "${facts.PREFIX} the confirm must be activated once: no submit action was sent " +
                    "(surface=[" + ui.surfaceTitle() + "])",
            )
        }
        log(
            if (ui.awaitExactText(facts.RESULT_CREATED, timeoutMillis = CREATED_NOTICE_WINDOW_MILLIS)) {
                "${facts.PREFIX} the created notice ${facts.RESULT_CREATED} was rendered (log only: the ledger is the judgement)"
            } else {
                "${facts.PREFIX} the created notice ${facts.RESULT_CREATED} was not rendered (log only: the ledger is the judgement)"
            },
        )
        val landed = awaitAddedTransaction(ledger, s0.formal.transactionTotal, seeded.knownTransactionIds, ui, log)
        assertEquals(
            "${facts.PREFIX} the confirmed refund must land on the ledger as a REFUND_RECEIPT",
            TransactionKind.REFUND_RECEIPT,
            landed.kind,
        )
        assertEquals(
            "${facts.PREFIX} the confirmed refund's occurred-at must be the pinned arrival instant",
            facts.ARRIVAL_INSTANT,
            landed.occurredAt,
        )
        assertEquals(
            "${facts.PREFIX} the confirmed refund's statistics-at must be the pinned arrival instant",
            facts.ARRIVAL_INSTANT,
            landed.statisticsAt,
        )

        // ---------------------------------------------------------------- L5: the ledger's own read face
        val s2 = ledger.snapshot()
        val added = ledger.addedTransaction(seeded.knownTransactionIds)
        assertEquals("${facts.PREFIX} the refund must add exactly one transaction", s0.formal.transactionTotal + 1, s2.formal.transactionTotal)
        assertEquals("${facts.PREFIX} the refund must land as a REFUND_RECEIPT", TransactionKind.REFUND_RECEIPT, added.kind)
        assertEquals("${facts.PREFIX} the pinned instant must be the refund's occurred-at", facts.ARRIVAL_INSTANT, added.occurredAt)
        assertEquals("${facts.PREFIX} the pinned instant must be the refund's statistics-at (the report bucket)", facts.ARRIVAL_INSTANT, added.statisticsAt)
        assertEquals("${facts.PREFIX} a refund carries exactly two legs", 2, added.legs.size)
        val arrivalLeg = added.legs.singleOrNull { leg -> leg.accountId == seeded.refundAccountId }
        checkNotNull(arrivalLeg) { "${facts.PREFIX} the refund must credit the chosen arrival account" }
        assertEquals(
            "${facts.PREFIX} the arrival leg must credit the exact minor units",
            P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            arrivalLeg.amount.minorUnits,
        )
        val expenseLeg = added.legs.singleOrNull { leg -> leg.accountId.value == DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID }
        checkNotNull(expenseLeg) { "${facts.PREFIX} the refund must reverse the inherited expense account" }
        assertEquals(
            "${facts.PREFIX} the inherited expense leg must reverse the same minor units",
            -P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            expenseLeg.amount.minorUnits,
        )
        assertEquals("${facts.PREFIX} the expense leg must inherit the original secondary category", "早餐", expenseLeg.categoryName)
        assertTrue("${facts.PREFIX} a refund carries no note", added.note.isNullOrEmpty())
        assertEquals(
            "${facts.PREFIX} the seeded transactions must keep their version ids",
            s0.versions,
            s2.versions.filter { version -> version.substringBefore("|") in seeded.knownTransactionIds },
        )
        assertEquals("${facts.PREFIX} the refund must add exactly one version row", s0.versions.size + 1, s2.versions.size)
        assertEquals(
            "${facts.PREFIX} the seeded postings must be unchanged",
            s0.formal.legs,
            s2.formal.legs.filter { leg -> leg.substringBefore("|") in seeded.knownTransactionIds },
        )
        assertEquals("${facts.PREFIX} the seed's candidate rows must be unchanged", s0.formal.candidates, s2.formal.candidates)
        assertEquals(
            "${facts.PREFIX} the four balances must be the seed's balances plus the refund's own legs",
            p807Q2Flow3BalancesAfter(s0.formal.balances, added.legs),
            s2.formal.balances,
        )
        log("${facts.PREFIX} after write " + s2.logLine())

        // ---------------------------------------------------------------- L6: the candidate empty state
        assertTrue(
            "${facts.PREFIX} the created notice must give way to the refreshed overview",
            ui.awaitHome(),
        )
        ui.goHome()
        ui.openExpenseEntry()
        assertTrue("${facts.PREFIX} the refund type row must be selectable again", ui.selectRefundType())
        assertTrue(
            "${facts.PREFIX} the consumed cap must empty the candidate page [${facts.CANDIDATES_EMPTY}]",
            ui.awaitAnywhereText(facts.CANDIDATES_EMPTY, timeoutMillis = CANDIDATE_TIMEOUT_MILLIS),
        )
        assertEquals("${facts.PREFIX} the port's candidate page must be empty after the full refund", 0, loadedCandidates(ledger).size)
        ui.closeEditor()

        // ---------------------------------------------------------------- L7: the arrival month's report
        ui.openAnalysis()
        assertTrue(
            "${facts.PREFIX} the analysis page must print the report region's independent-period statement",
            ui.awaitAnywhereText(facts.REPORT_PERIOD_NOTICE),
        )
        assertTrue("${facts.PREFIX} the 自定义 chip must reveal the two window inputs", ui.openCustomWindow())
        assertTrue(
            "${facts.PREFIX} the custom window's lower bound must be typeable",
            ui.setFieldByLabel(facts.CUSTOM_START_LABEL, facts.NOVEMBER_START.toString()),
        )
        assertTrue(
            "${facts.PREFIX} the custom window's upper bound must be typeable",
            ui.setFieldByLabel(facts.CUSTOM_END_LABEL, facts.NOVEMBER_END.toString()),
        )
        assertCustomWindowBounds(
            ui,
            facts.NOVEMBER_START.toString(),
            facts.NOVEMBER_END.toString(),
            "the arrival month's custom window",
        )
        val november = ledger.report(facts.novemberPeriod())
        val novemberCny = cnyRow(november)
        assertEquals("${facts.PREFIX} the arrival month must count exactly the one refund", 1, november.transactionCount)
        assertEquals("${facts.PREFIX} the arrival month must carry no ordinary income", 0L, novemberCny.ordinaryIncomeMinorUnits)
        assertEquals(
            "${facts.PREFIX} the arrival month's net expense must be the refund's negative contribution",
            -P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            novemberCny.netExpenseMinorUnits,
        )
        assertEquals(
            "${facts.PREFIX} the arrival month's balance must be ordinary income minus net expense",
            P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            novemberCny.balanceMinorUnits,
        )
        assertTrue(
            "${facts.PREFIX} the custom commit must land the November window",
            ui.commitCustomWindow(p807Q2Flow3PeriodLine(november, P806PeriodKind.CUSTOM)),
        )
        assertUiLines(ui, p807Q2Flow3ReportUiLines(november, P806PeriodKind.CUSTOM), "the arrival month's report")

        // ---------------------------------------------------------------- L8: the original expense's month is unchanged
        ui.scrollBackTo(facts.CUSTOM_START_LABEL)
        assertTrue(
            "${facts.PREFIX} the custom window's lower bound must be re-typeable",
            ui.setFieldByLabel(facts.CUSTOM_START_LABEL, facts.OCTOBER_START.toString()),
        )
        assertTrue(
            "${facts.PREFIX} the custom window's upper bound must be re-typeable",
            ui.setFieldByLabel(facts.CUSTOM_END_LABEL, facts.OCTOBER_END.toString()),
        )
        assertCustomWindowBounds(
            ui,
            facts.OCTOBER_START.toString(),
            facts.OCTOBER_END.toString(),
            "the original expense month's custom window",
        )
        val octoberAfter = ledger.report(facts.octoberPeriod())
        val octoberAfterFace = p807Q2Flow3ReportLines(octoberAfter)
        assertTrue(
            "${facts.PREFIX} the custom commit must land the October window",
            ui.commitCustomWindow(p807Q2Flow3PeriodLine(octoberAfter, P806PeriodKind.CUSTOM)),
        )
        assertSeededOctoberFace(octoberAfter)
        assertUiLines(ui, p807Q2Flow3ReportUiLines(octoberAfter, P806PeriodKind.CUSTOM), "the original expense month's report")
        assertEquals(
            "${facts.PREFIX} the original expense's month must read back item by item",
            octoberBeforeFace,
            octoberAfterFace,
        )

        // ---------------------------------------------------------------- L9: the calendar and the day detail
        ui.goHome()
        assertStep(
            ui,
            "homeMonthPrevious",
            "one HOME step must land the domain's first month [${facts.HOME_MONTH_FIRST}]",
            ui.homeMonthStepPrevious(),
        ) { ui.monthRowReport() }
        assertStep(ui, "openCalendar", "the calendar's own HOME entry must open it", ui.openCalendar())
        assertStep(
            ui,
            "calendarFirstMonth",
            "the calendar must open on the pinned first month [${facts.CALENDAR_MONTH_FIRST}]",
            ui.awaitExactText(facts.CALENDAR_MONTH_FIRST),
        )
        assertStep(
            ui,
            "calendarNextMonth",
            "the calendar's own next step must reach the arrival month [${facts.CALENDAR_MONTH_ARRIVAL}]",
            ui.calendarNextMonth(facts.CALENDAR_MONTH_ARRIVAL),
        )
        assertStep(
            ui,
            "calendarArrivalMonth",
            "one calendar step must reach the arrival month [${facts.CALENDAR_MONTH_ARRIVAL}]",
            ui.awaitExactText(facts.CALENDAR_MONTH_ARRIVAL),
        )
        val arrivalCalendar = ledger.calendar(NOVEMBER)
        val arrivalDay = arrivalDayOf(arrivalCalendar)
        val dayCellText = p807Q2Flow3CellDescription(arrivalDay)
        assertEquals(
            "${facts.PREFIX} the arrival day's cell must be the pinned accessibility text",
            facts.ARRIVAL_DAY_CELL,
            dayCellText,
        )
        assertEquals("${facts.PREFIX} the arrival day must carry exactly one contribution", 1, arrivalDay.transactionCount)
        assertStep(
            ui,
            "arrivalDayCell",
            "the arrival day's cell must render its exact accessibility text",
            ui.awaitExactText(dayCellText, seek = true),
        )
        assertStep(ui, "openDayCell", "the arrival day's cell must open the day detail", ui.openDayCell(dayCellText))
        assertStep(
            ui,
            "drilldownCountOne",
            "the day detail must count exactly one row [${facts.DRILLDOWN_COUNT_ONE}]",
            ui.awaitExactText(facts.DRILLDOWN_COUNT_ONE, seek = true),
        )
        assertStep(
            ui,
            "dayDetailHeader",
            "the day detail's header must name the day [${facts.DAY_DETAIL_PREFIX}]",
            ui.awaitPrefixText(facts.DAY_DETAIL_PREFIX, seek = true),
        )
        val dayDrilldown = ledger.drilldown(arrivalWindow)
        assertEquals("${facts.PREFIX} the arrival day's detail must count the refund", 1, dayDrilldown.totalCount)
        val arrivalRow = dayDrilldown.page.rows.singleOrNull()
        checkNotNull(arrivalRow) { "${facts.PREFIX} the arrival day's page must carry the one row" }
        assertEquals("${facts.PREFIX} the day's one row must be the refund", added.transactionId, arrivalRow.transactionId)
        assertEquals("${facts.PREFIX} the day's one row must read as REFUND_RECEIPT", TransactionKind.REFUND_RECEIPT, arrivalRow.kind)
        assertEquals("${facts.PREFIX} the day's one row must carry the pinned statistics instant", facts.ARRIVAL_INSTANT, arrivalRow.statisticsAt)
        assertStep(
            ui,
            "dayGroupRow",
            "the day detail must group the row under its own day [${facts.ARRIVAL_DAY} · 1 笔]",
            ui.awaitExactText("${facts.ARRIVAL_DAY} · 1 笔", seek = true),
        )
        val headline = p807Q2Flow3RowHeadline(arrivalRow)
        assertStep(
            ui,
            "rowHeadline",
            "the day detail must print the row's exact headline [$headline]",
            ui.awaitExactText(headline, seek = true),
        )
        p807Q2Flow3PostingLines(added.legs).forEach { line ->
            assertStep(
                ui,
                "postingLine",
                "the day detail must print the exact posting line [$line]",
                ui.awaitExactText(line, seek = true),
            )
        }

        // ---------------------------------------------------------------- L10: the detail round trip writes nothing
        ui.scrollBackTo(facts.DAY_DETAIL_PREFIX)
        assertStep(ui, "openRowDetail", "the row's read-only detail must open", ui.openRowDetail(headline))
        assertStep(
            ui,
            "backToDayDetail",
            "the first 返回 must land on the same day detail",
            ui.backVerified("backToDayDetail") { ui.hasExactText(headline) },
        )
        ui.scrollBackTo(facts.DAY_DETAIL_PREFIX)
        assertStep(
            ui,
            "returnedCountOne",
            "the returned day detail must still count one row",
            ui.awaitExactText(facts.DRILLDOWN_COUNT_ONE),
        )
        assertStep(
            ui,
            "backToCalendar",
            "the second 返回 must close the day detail",
            ui.backVerified("backToCalendar") { !ui.hasExactText(facts.DRILLDOWN_TITLE) && !ui.hasExactText(facts.DETAIL_TITLE) },
        )
        ui.scrollBackTo(facts.CALENDAR_TITLE)
        assertStep(
            ui,
            "returnedCalendarMonth",
            "the returned calendar must still stand on the arrival month",
            ui.awaitExactText(facts.CALENDAR_MONTH_ARRIVAL),
        )
        val s3 = ledger.snapshot()
        assertEquals("${facts.PREFIX} the detail round trip must write nothing: the whole read must equal the post-write read", s2, s3)
        assertEquals(
            "${facts.PREFIX} the calendar face must be unchanged by the calendar legs",
            p807Q2Flow3CalendarLines(arrivalCalendar),
            p807Q2Flow3CalendarLines(ledger.calendar(NOVEMBER)),
        )
        log(
            "${facts.PREFIX} PASS refund=${added.transactionId.value} october=[" + octoberAfterFace.joinToString(" ¦ ") + "] " +
                "transactions=${s3.formal.transactionTotal} postings=${s3.formal.legs.size}",
        )
    }

    // ------------------------------------------------------------------ read helpers

    /** The first candidate page's rows, or a loud failure when the read cannot answer (never an empty claim). */
    private fun loadedCandidates(ledger: P807Q2Flow3Ledger): List<ManualRefundCandidate> =
        when (val result = ledger.refundCandidates()) {
            is ManualRefundCandidatesResult.Loaded -> result.candidates
            ManualRefundCandidatesResult.Unavailable -> error("the refund candidate read answered Unavailable; the flow cannot claim an empty page")
        }

    /** The report face's single CNY row, or a loud failure (a missing axis is not a zero). */
    private fun cnyRow(view: P806ReportView): P806CurrencyView =
        view.currencies.singleOrNull { row -> row.currency.code == CNY }
            ?: error("the report's currency axis must carry exactly one CNY row: ${view.currencies.map { row -> row.currency.code }}")

    /** The arrival day's calendar row, or a loud failure (every date of the month carries one). */
    private fun arrivalDayOf(view: P806CalendarView): P806CalendarDayView =
        view.days.singleOrNull { day -> day.date == P807Q2Flow3Facts.ARRIVAL_DAY }
            ?: error("the calendar of ${view.month} must carry the arrival day ${P807Q2Flow3Facts.ARRIVAL_DAY}")

    /**
     * L4's judgement: a LOUD, bounded ledger read-back of the one transaction the confirm must add.
     *
     * The window retries only the reading that shows NO new transaction: the confirm's write is
     * committed on the app's own coroutine, so a read can legitimately arrive before it, and the
     * operation lease is acquired with `tryLock`, so a read issued while the app is mid-operation
     * answers nothing at all ([P807Q2Flow3Ledger.snapshotWhenLeasable] reports that as null rather
     * than as an empty ledger). The FIRST read that shows one new transaction ends the window and
     * resolves that transaction through the flow-1 difference helper, so a landed-but-unexpected row
     * fails on the caller's own assertions at once instead of idling to the deadline.
     *
     * The failure names exactly what the next round needs: the baseline and the last good total, how
     * many reads never got a lease, the candidate rows and the visible surface, and the window DUMP —
     * the evidence that tells a lost write apart from a lost screen.
     */
    private fun awaitAddedTransaction(
        ledger: P807Q2Flow3Ledger,
        baselineTransactionTotal: Int,
        knownTransactionIds: Set<String>,
        ui: P807Q2Flow3Ui,
        log: (String) -> Unit,
    ): TransactionDetail {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + LEDGER_LANDING_TIMEOUT_MILLIS
        var reads = 0
        var withoutLease = 0
        var observed = baselineTransactionTotal
        while (SystemClock.elapsedRealtime() < deadline) {
            reads++
            val snapshot = ledger.snapshotWhenLeasable()
            if (snapshot == null) {
                withoutLease++
            } else {
                observed = snapshot.formal.transactionTotal
                if (observed == baselineTransactionTotal + 1) {
                    val added = ledger.addedTransactionWhenLeasable(knownTransactionIds)
                    if (added != null) {
                        log(
                            "${P807Q2Flow3Facts.PREFIX} the confirm's write read back after " +
                                (SystemClock.elapsedRealtime() - started) + "ms reads=" + reads + " withoutLease=" + withoutLease,
                        )
                        return added
                    }
                    withoutLease++
                } else if (observed > baselineTransactionTotal + 1) {
                    break
                }
            }
            SystemClock.sleep(LEDGER_POLL_MILLIS)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        throw AssertionError(
            "${P807Q2Flow3Facts.PREFIX} the confirmed refund must add exactly ONE ledger transaction within " +
                LEDGER_LANDING_TIMEOUT_MILLIS + "ms: baseline=" + baselineTransactionTotal + " lastRead=" + observed +
                " reads=" + reads + " withoutLease=" + withoutLease + " elapsed=" + elapsed + "ms " +
                "candidates=[" + candidateRowsForFailure(ledger) + "] surface=[" + ui.surfaceTitle() + "] " +
                "dump=" + failureDump(ui, "confirmLegal:no-ledger-write"),
        )
    }

    /**
     * L7/L8's field evidence: BOTH custom-window bounds read back from ONE tree, BEFORE the window is
     * committed. One field's `landed=true` is not evidence — run `45687641567` logged both writes as
     * landed while the upper field still held the lower bound (the two editors are adjacent rows and
     * the former locator flipped between them), then committed a one-day window that no report line
     * could ever match. A pair that does not read back exactly fails here, with both actual values and
     * the DUMP, before the commit can hide the cause behind a landing judgement.
     */
    private fun assertCustomWindowBounds(
        ui: P807Q2Flow3Ui,
        start: String,
        end: String,
        what: String,
    ) {
        val (startText, endText) = ui.customWindowTexts(P807Q2Flow3Facts.CUSTOM_START_LABEL, P807Q2Flow3Facts.CUSTOM_END_LABEL)
        if (startText == start && endText == end) return
        throw AssertionError(
            "${P807Q2Flow3Facts.PREFIX} $what must read back BOTH custom-window bounds from ONE tree: " +
                "start=[$startText] expected=[$start] end=[$endText] expected=[$end] " +
                "dump=" + failureDump(ui, "customWindow:$what"),
        )
    }

    /** The truncation-marked DUMP a failure message carries (the whole one is in logcat, chunked). */
    private fun failureDump(
        ui: P807Q2Flow3Ui,
        label: String,
    ): String {
        val dump = ui.dump(label)
        return if (dump.length > FAILURE_DUMP_CHARS) {
            dump.take(FAILURE_DUMP_CHARS) + "…(dump truncated at $FAILURE_DUMP_CHARS of ${dump.length} chars; the whole DUMP is in logcat)"
        } else {
            dump
        }
    }

    /**
     * L9/L10's failure shape: the message carries the window DUMP, so a red leg leaves a reading
     * instead of a guess — run `45702988967` failed the first calendar step on a bare assertion with
     * no dump at all. [detail] is evaluated only on failure and carries step-specific readings such
     * as [P807Q2Flow3Ui.monthRowReport].
     */
    private fun assertStep(
        ui: P807Q2Flow3Ui,
        step: String,
        what: String,
        ok: Boolean,
        detail: () -> String = { "" },
    ) {
        if (ok) return
        val extra = detail()
        throw AssertionError(
            "${P807Q2Flow3Facts.PREFIX} $what (step=$step" + (if (extra.isEmpty()) "" else " " + extra) + ") " +
                "dump=" + failureDump(ui, "step:$step"),
        )
    }

    /** The candidate rows a failure message carries: an unavailable or unreadable page is NAMED, never a zero. */
    private fun candidateRowsForFailure(ledger: P807Q2Flow3Ledger): String =
        runCatching {
            when (val result = ledger.refundCandidates()) {
                is ManualRefundCandidatesResult.Loaded ->
                    if (result.candidates.isEmpty()) {
                        "empty"
                    } else {
                        result.candidates.joinToString(";") { candidate -> p807Q2Flow3CandidateRowText(candidate) }
                    }
                ManualRefundCandidatesResult.Unavailable -> "unavailable"
            }
        }.getOrElse { failure -> "unreadable(" + failure.message + ")" }

    /** The seeded expense's own month, which the refund must leave untouched. */
    private fun assertSeededOctoberFace(view: P806ReportView) {
        val row = cnyRow(view)
        assertEquals("${P807Q2Flow3Facts.PREFIX} the original expense's month must count exactly that expense", 1, view.transactionCount)
        assertEquals("${P807Q2Flow3Facts.PREFIX} the original expense's month must carry no ordinary income", 0L, row.ordinaryIncomeMinorUnits)
        assertEquals(
            "${P807Q2Flow3Facts.PREFIX} the original expense's month must read its net expense",
            P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
            row.netExpenseMinorUnits,
        )
        assertEquals("${P807Q2Flow3Facts.PREFIX} the original expense's month must count exactly one CNY transaction", 1, row.transactionCount)
    }

    /** Asserts that every payload-derived line is on the surface, in the region's own document order. */
    private fun assertUiLines(
        ui: P807Q2Flow3Ui,
        lines: List<String>,
        what: String,
    ) {
        lines.forEach { line ->
            assertTrue(
                "${P807Q2Flow3Facts.PREFIX} $what must render the payload's exact line [$line]",
                ui.awaitExactText(line, seek = true),
            )
        }
    }

    private companion object {
        const val TAG = "UnifiedLedger"

        /** The ledger's only managed currency on this seed. */
        const val CNY = "CNY"

        /** The arrival month every calendar read of this flow asks for. */
        val NOVEMBER: YearMonth = YearMonth(2026, 11)

        /** The candidate read is asynchronous (a `LaunchedEffect`); the wait is bounded, never open. */
        const val CANDIDATE_TIMEOUT_MILLIS = 60_000L

        /** How much of a failure-time DUMP the assertion message carries (the whole one is in logcat). */
        const val FAILURE_DUMP_CHARS = 700

        /** How long the created notice may take to render: a logged signal only, never a judgement. */
        const val CREATED_NOTICE_WINDOW_MILLIS = 2_000L

        /** How long the ledger may take to show the confirmed write, end to end. */
        const val LEDGER_LANDING_TIMEOUT_MILLIS = 30_000L

        /** The ledger poll interval inside that window (a bounded await, never a fixed sleep). */
        const val LEDGER_POLL_MILLIS = 500L
    }
}
