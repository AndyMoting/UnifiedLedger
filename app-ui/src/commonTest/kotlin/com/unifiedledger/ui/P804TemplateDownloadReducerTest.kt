package com.unifiedledger.ui

import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P8-04 i1 (D-11; spec `docs/specs/2026-11-14-p8-04-self-entry-import-design.md`): the
 * `RequestImportTemplateDownload` event family — the import surface's 「获取导入模板」 entry.
 * Table 6.2a discipline: the designed effect is the OverviewEmpty request intent (不切态 — the
 * state is unchanged, the coordinator/testable host action owns the SAF launch, the
 * `StartImportFilePick` mechanism), absorbed in every other state, never an ISE.
 */
class P804TemplateDownloadReducerTest {
    private val emptyState = LedgerCurrentState(LedgerId("ledger-test"), emptyList(), emptyList())
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), CurrencyUnit("CNY", 2))

    @Test
    fun theRequestIntentIsAbsorbedOnOverviewEmptyTheHostOwnsTheLaunch() {
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, importSurface = true)
        val reduced = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.RequestImportTemplateDownload))
        // 不切态: the state instance is value-identical — the SAF launch + write is the host's
        // effect, never a reducer transition (the StartImportFilePick mechanism).
        assertEquals(overview, reduced)
        assertEquals(P503Tab.HOME, reduced.selectedTab)
        assertTrue(reduced.importSurface)
    }

    @Test
    fun theRequestIntentIsAbsorbedInEveryOtherState() {
        // The absorb family: a sample of every state class outside the designed effect (the
        // reducer's absorb lists). None may throw (table 6.2a), and none may transition.
        val draft = ExpenseDraft(paymentAccountId = null, categoryId = null, amountText = "", occurredAt = null)
        val requestId = RequestId("request-1")
        val states =
            listOf(
                P503AppState.Ready(),
                P503AppState.Editing(draft, requestId, emptyState, P503Tab.HOME),
                P503AppState.AwaitingConfirmation(draft, requestId, emptyState, P503Tab.HOME),
                P503AppState.Submitting(draft, requestId, emptyState, P503Tab.HOME),
                P503AppState.TransactionDetail(P503AppState.OverviewEmpty(emptyState), P503Tab.HOME, TransactionId("tx-1"), com.unifiedledger.application.TransactionDetailResult.NotFound),
                P503AppState.BackupExport(P503AppState.OverviewEmpty(emptyState)),
                P503AppState.ImportCandidateDetail(
                    overview = P503AppState.OverviewEmpty(emptyState),
                    candidateId = com.unifiedledger.application.ImportCandidateId("candidate-1"),
                    detail = com.unifiedledger.application.ImportCandidateDetailResult.Absent,
                    duplicates = com.unifiedledger.application.ImportDuplicateReviewsResult.Absent,
                    form = ImportDecisionDraft(),
                ),
            )
        states.forEach { state ->
            assertEquals(state, reducer.reduce(state, P503UiEvent.RequestImportTemplateDownload), "absorbed on ${state::class.simpleName}")
        }
    }

    @Test
    fun theEntryDoesNotSwitchTheStateOrStartAnyFlow() {
        // V-3 alignment (确认前零正式效果): a template download carries zero formal-ledger
        // effect — the reducer observable is exactly the absorbed column (the state the same
        // value), so a download can never move the import surface, the tab cursor or any
        // projection.
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.ACCOUNTS, importSurface = true)
        val reduced = reducer.reduce(overview, P503UiEvent.RequestImportTemplateDownload)
        assertEquals(overview, reduced)
    }
}
