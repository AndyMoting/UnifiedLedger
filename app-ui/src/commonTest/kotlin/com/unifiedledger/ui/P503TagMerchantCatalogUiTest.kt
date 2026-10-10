package com.unifiedledger.ui

import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantFailureCode
import com.unifiedledger.application.TagMerchantReceiptOutcome
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-08 08.C tag/merchant catalog-management UI evidence (R-222-11; spec open item 11; P708-A02
 * presentation intent — the vector registration belongs to the main agent). The pins:
 *
 * - the six dialogs open/close through the same catalogDialog state the account/category family
 *   uses, and the four-state command outcome becomes the banner (Accepted/NoChange succeed,
 *   Rejected/Conflict show the frozen failure-code text);
 * - the tombstone split is the PRODUCTION split: live items are actionable, tombstoned items go
 *   to the read-only audit list — no rename/disable/delete entry exists for them (open item 11);
 * - the frozen `TagMerchantFailureCode` literals map to their readable text, including the
 *   reference-rule wording for the tombstone rejection.
 */
class P503TagMerchantCatalogUiTest {
    private val ledgerId = LedgerId("ledger-08c-catalog")
    private val currentState = LedgerCurrentStateForTests.empty(ledgerId)
    private val overview = P503AppState.OverviewEmpty(state = currentState)
    private val reducer: P503Reducer =
        P503ReducerImpl(
            com.unifiedledger.application.ParseManualExpenseAmount(),
            com.unifiedledger.domain.CurrencyUnit("CNY", 2),
            com.unifiedledger.application.LedgerClock { kotlin.time.Instant.parse("2026-03-15T00:00:00Z") },
            com.unifiedledger.application.EntryExpressionEvaluator(),
        )

    // ---- dialogs (the four-state dialog lifecycle: open → edit → submit/land → closed) ----

    @Test
    fun createDialogsOpenCollectTextAndCloseOnTheLandedResult() {
        val openTag = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenTagCreateDialog))
        assertEquals(CatalogDialog.CreateTagItem(), openTag.catalogDialog)
        val typed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(openTag, P503UiEvent.UpdateCatalogFormText("交通")))
        assertEquals("交通", (typed.catalogDialog as CatalogDialog.CreateTagItem).nameText)

        val openMerchant = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenMerchantCreateDialog))
        assertEquals(CatalogDialog.CreateMerchantItem(), openMerchant.catalogDialog)

        // The landed ACCEPTED result closes the dialog and shows the success banner.
        val accepted =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(typed, P503UiEvent.TagMerchantCommandLanded(acceptedResult("tag-1"))),
            )
        assertEquals(CatalogDialog.None, accepted.catalogDialog)
        assertEquals(false, accepted.catalogNotice?.error)
    }

    @Test
    fun renameDialogsCarryTheCapturedNameAndDeleteDialogsArePlainConfirms() {
        val rename = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenTagRenameDialog("tag-1", "交通")))
        assertEquals(CatalogDialog.RenameTagItem("tag-1", "交通"), rename.catalogDialog)
        val merchantRename =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenMerchantRenameDialog("merchant-1", "超市")))
        assertEquals(CatalogDialog.RenameMerchantItem("merchant-1", "超市"), merchantRename.catalogDialog)

        val deleteTag = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenTagDeleteDialog("tag-1", "交通")))
        assertEquals(CatalogDialog.ConfirmTagItemDelete("tag-1"), deleteTag.catalogDialog)
        val deleteMerchant =
            assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenMerchantDeleteDialog("merchant-1", "超市")))
        assertEquals(CatalogDialog.ConfirmMerchantItemDelete("merchant-1"), deleteMerchant.catalogDialog)
    }

    @Test
    fun aRejectedCommandSurfacesTheFrozenFailureTextAndClosesTheDialog() {
        val open = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenTagCreateDialog))
        val landed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    open,
                    P503UiEvent.TagMerchantCommandLanded(
                        TagMerchantCommandResult.Rejected(TagMerchantFailureCode.TAG_MERCHANT_HAS_REFERENCES),
                    ),
                ),
            )
        assertEquals(CatalogDialog.None, landed.catalogDialog)
        val notice = assertIs<CatalogNotice>(landed.catalogNotice)
        assertTrue(notice.error)
        assertEquals(tagMerchantFailureMessage(TagMerchantFailureCode.TAG_MERCHANT_HAS_REFERENCES), notice.message)
    }

    @Test
    fun commandEventsAreAbsorbedOutsideTheOverview() {
        // The management events never reach another state as an ISE (the absorb discipline).
        val editing = P503AppState.Editing(draft = com.unifiedledger.application.ExpenseDraft(AccountId("a"), com.unifiedledger.domain.CategoryId("c"), "1.00", null), requestId = null)
        assertEquals(
            editing,
            reducer.reduce(editing, P503UiEvent.TagMerchantCommandLanded(acceptedResult("tag-1"))),
        )
        assertEquals(editing, reducer.reduce(editing, P503UiEvent.OpenTagCreateDialog))
    }

    // ---- the tombstone audit split (open item 11) ----

    @Test
    fun theAuditSplitPutsOnlyTombstonedItemsBeyondAction() {
        val live = catalogItem("tag-live", active = true, tombstoned = false)
        val disabled = catalogItem("tag-disabled", active = false, tombstoned = false)
        val tombstoned = catalogItem("tag-dead", active = false, tombstoned = true)
        val items = listOf(live, disabled, tombstoned)
        // Live = actionable (改名/停用/删除 offered); disabled stays actionable (reversible).
        assertEquals(setOf("tag-live", "tag-disabled"), tagMerchantLiveItems(items).map { it.id }.toSet())
        // Audit = read-only (no rename/disable/delete affordance is rendered for these rows).
        assertEquals(setOf("tag-dead"), tagMerchantAuditItems(items).map { it.id }.toSet())
        assertTrue(tagMerchantLiveItems(items).none { it.tombstoned })
    }

    // ---- the frozen failure-code text mapping ----

    @Test
    fun everyFrozenFailureCodeHasReadableText() {
        TagMerchantFailureCode.entries.forEach { code ->
            val text = tagMerchantFailureMessage(code)
            assertTrue(text.isNotEmpty(), "$code must carry readable text")
        }
        // The tombstone rejection states the reference rule (已有引用改用停用), never jargon.
        assertTrue(tagMerchantFailureMessage(TagMerchantFailureCode.TAG_MERCHANT_HAS_REFERENCES).contains("停用"))
        // The revision conflict shares the catalog family's refresh wording.
        assertEquals("目录已变化，请刷新", tagMerchantFailureMessage(TagMerchantFailureCode.TAG_MERCHANT_REVISION_CONFLICT))
    }

    @Test
    fun noticeForMapsTheFourStates() {
        assertEquals(false, tagMerchantNoticeFor(acceptedResult("tag-1")).error)
        assertEquals(false, tagMerchantNoticeFor(noChangeResult("tag-1")).error)
        assertEquals(true, tagMerchantNoticeFor(TagMerchantCommandResult.Rejected(TagMerchantFailureCode.TAG_MERCHANT_NAME_EMPTY)).error)
        assertEquals(true, tagMerchantNoticeFor(TagMerchantCommandResult.Conflict(TagMerchantFailureCode.REQUEST_IDENTITY_CONFLICT)).error)
    }

    // ---- fixtures ----

    private fun acceptedResult(itemId: String): TagMerchantCommandResult.Accepted =
        TagMerchantCommandResult.Accepted(
            com.unifiedledger.application.TagMerchantCommandReceipt(
                requestId = com.unifiedledger.application.TagMerchantRequestId("request-1"),
                outcome = TagMerchantReceiptOutcome.ACCEPTED,
                kind = CatalogItemKind.TAG,
                itemId = itemId,
                newRevision = 1L,
            ),
        )

    private fun noChangeResult(itemId: String): TagMerchantCommandResult.NoChange =
        TagMerchantCommandResult.NoChange(
            com.unifiedledger.application.TagMerchantCommandReceipt(
                requestId = com.unifiedledger.application.TagMerchantRequestId("request-1"),
                outcome = TagMerchantReceiptOutcome.NO_CHANGE,
                kind = CatalogItemKind.TAG,
                itemId = itemId,
                newRevision = 1L,
            ),
        )

    private fun catalogItem(
        id: String,
        active: Boolean,
        tombstoned: Boolean,
    ): CatalogItem =
        CatalogItem(
            kind = CatalogItemKind.TAG,
            id = id,
            ledgerId = ledgerId,
            name = id,
            active = active,
            tombstoned = tombstoned,
            revision = 1L,
        )
}
