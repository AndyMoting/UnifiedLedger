package com.unifiedledger.ui

import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.CatalogCommandReceipt
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogFailureCode
import com.unifiedledger.application.CatalogReceiptOutcome
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CategoryTreeView
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-01.D pure reducer coverage for the catalog management fields on the overview (spec sections
 * 7.1-7.4). These mirror the P503ReducerTest style: no Compose, no IO, only (state, event)
 * transitions.
 */
class P503CatalogManagementReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val accountId = AccountId("asset-payment-local")
    private val groupId = CategoryId("expense-category-food")
    private val leafId = CategoryId("expense-category-breakfast")
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val overview = LedgerCurrentState(ledgerId, transactions = emptyList<CurrentVersionRow>(), balances = emptyList())

    private fun snapshot(version: Long = 1L) =
        CatalogSnapshotView(
            catalogVersion = version,
            manageableAccounts =
                listOf(
                    ManageableAccountView(
                        accountId = accountId,
                        name = "默认支付账户",
                        kind = AccountKind.ASSET,
                        currency = cny,
                        active = true,
                        balanceMinorUnits = null,
                    ),
                ),
            categories =
                listOf(
                    CategoryTreeView(leafId, groupId, "早餐", CategoryKind.EXPENSE, active = true, postingAccountId = AccountId("expense-account-local")),
                    CategoryTreeView(groupId, null, "餐饮", CategoryKind.EXPENSE, active = true, postingAccountId = null),
                ),
        )

    /** The ACCOUNTS tab state with management open, as the host produces it after reading. */
    private fun management(
        dialog: CatalogDialog = CatalogDialog.None,
        notice: CatalogNotice? = null,
        version: Long = 1L,
    ) = P503AppState.OverviewEmpty(
        state = overview,
        selectedTab = P503Tab.ACCOUNTS,
        catalogSnapshot = snapshot(version),
        catalogDialog = dialog,
        catalogNotice = notice,
    )

    private fun reduceFrom(
        state: P503AppState,
        vararg events: P503UiEvent,
    ): P503AppState = events.fold(state) { current, event -> reducer.reduce(current, event) }

    @Test
    fun selectingAccountsTabInstallsTheAuthoritativeSnapshot() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    P503AppState.OverviewEmpty(overview, P503Tab.HOME),
                    P503UiEvent.SelectTab(P503Tab.ACCOUNTS, snapshot()),
                ),
            )
        assertEquals(P503Tab.ACCOUNTS, state.selectedTab)
        assertEquals(snapshot(), state.catalogSnapshot)
        assertEquals(CatalogDialog.None, state.catalogDialog)
        assertNull(state.catalogNotice)
    }

    @Test
    fun selectingOtherTabClearsAnyOpenDialogAndNotice() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(dialog = CatalogDialog.CreateAccount(), notice = CatalogNotice("x", true)),
                    P503UiEvent.SelectTab(P503Tab.ANALYSIS),
                ),
            )
        assertEquals(P503Tab.ANALYSIS, state.selectedTab)
        assertEquals(CatalogDialog.None, state.catalogDialog)
        assertNull(state.catalogNotice)
        assertEquals(snapshot(), state.catalogSnapshot)
    }

    @Test
    fun createAccountDialogDefaultsToAssetKind() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(management(), P503UiEvent.OpenAccountCreateDialog()),
            )
        assertEquals(CatalogDialog.CreateAccount(kind = AccountKind.ASSET), state.catalogDialog)
        assertNull(state.catalogNotice)
    }

    @Test
    fun createAccountFormTextAndKindUpdatesApply() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.OpenAccountCreateDialog(),
                    P503UiEvent.UpdateCatalogFormText("现金"),
                    P503UiEvent.UpdateCatalogFormKind(AccountKind.LIABILITY),
                ),
            )
        assertEquals(CatalogDialog.CreateAccount(nameText = "现金", kind = AccountKind.LIABILITY), state.catalogDialog)
    }

    @Test
    fun renameAccountDialogPrefillsCurrentName() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.OpenAccountRenameDialog(accountId, "默认支付账户"),
                ),
            )
        assertEquals(CatalogDialog.RenameAccount(accountId, "默认支付账户"), state.catalogDialog)
    }

    @Test
    fun groupDialogStartsEmptyInBothFields() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(management(), P503UiEvent.OpenCategoryGroupDialog(CategoryKind.EXPENSE)),
            )
        assertEquals(CatalogDialog.CreateCategoryGroup(CategoryKind.EXPENSE, "", ""), state.catalogDialog)
    }

    @Test
    fun secondaryTextAppliesOnlyToGroupDialog() {
        val child =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.OpenCategoryAppendChildDialog(groupId),
                    P503UiEvent.UpdateCatalogFormSecondaryText("被忽略"),
                    P503UiEvent.UpdateCatalogFormText("奶茶"),
                ),
            )
        assertEquals(CatalogDialog.AppendCategoryChild(groupId, "奶茶"), child.catalogDialog)

        val group =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    child,
                    P503UiEvent.OpenCategoryGroupDialog(CategoryKind.INCOME),
                    P503UiEvent.UpdateCatalogFormSecondaryText("工资"),
                ),
            )
        assertEquals(CatalogDialog.CreateCategoryGroup(CategoryKind.INCOME, "", "工资"), group.catalogDialog)
    }

    @Test
    fun dismissDialogReturnsToNoneWithoutLeavingTheTab() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.OpenAccountCreateDialog(),
                    P503UiEvent.DismissCatalogDialog,
                ),
            )
        assertEquals(CatalogDialog.None, state.catalogDialog)
        assertEquals(P503Tab.ACCOUNTS, state.selectedTab)
    }

    @Test
    fun acceptedCommandStoresFreshSnapshotAndSuccessNotice() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.OpenAccountCreateDialog(),
                    P503UiEvent.CatalogCommandCompleted(accepted(), snapshot(version = 2L)),
                ),
            )
        assertEquals(2L, state.catalogSnapshot?.catalogVersion)
        assertEquals(CatalogDialog.None, state.catalogDialog)
        assertEquals(CatalogNotice("已保存", error = false), state.catalogNotice)
    }

    @Test
    fun noChangeCommandAlsoReportsSuccessWithoutDuplicateCreation() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.CatalogCommandCompleted(
                        CatalogCommandResult.NoChange(
                            CatalogCommandReceipt(CatalogRequestId("r"), CatalogReceiptOutcome.NO_CHANGE, 1L),
                        ),
                        snapshot(),
                    ),
                ),
            )
        assertEquals(CatalogNotice("已保存", error = false), state.catalogNotice)
    }

    @Test
    fun typedRejectionShowsMappedChineseMessage() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.CatalogCommandCompleted(
                        CatalogCommandResult.Rejected(CatalogFailureCode.CATALOG_NAME_CONFLICT),
                        snapshot(),
                    ),
                ),
            )
        assertEquals(CatalogNotice("名称已存在，请换一个", error = true), state.catalogNotice)
    }

    @Test
    fun categoryReferenceRejectionAdvisesDeactivation() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.CatalogCommandCompleted(
                        CatalogCommandResult.Rejected(CatalogFailureCode.CATEGORY_HAS_REFERENCES),
                        snapshot(),
                    ),
                ),
            )
        assertEquals("已有引用，不能删除，请改用停用", state.catalogNotice?.message)
    }

    @Test
    fun lastActiveChildRejectionAsksToKeepOneUsableChild() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(),
                    P503UiEvent.CatalogCommandCompleted(
                        CatalogCommandResult.Rejected(CatalogFailureCode.CATEGORY_HAS_NO_ACTIVE_CHILD),
                        snapshot(),
                    ),
                ),
            )
        assertEquals("需保留至少一个可用子分类", state.catalogNotice?.message)
    }

    @Test
    fun versionConflictShowsRefreshMessageAndInstallsFreshSnapshotWithoutRetry() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(version = 1L),
                    P503UiEvent.OpenAccountCreateDialog(),
                    P503UiEvent.CatalogCommandCompleted(
                        CatalogCommandResult.Conflict(CatalogFailureCode.CATALOG_VERSION_CONFLICT),
                        snapshot(version = 5L),
                    ),
                ),
            )
        assertEquals(CatalogNotice("目录已变化，请刷新", error = true), state.catalogNotice)
        // The refreshed snapshot is already rendered; the stale write is abandoned, not retried.
        assertEquals(5L, state.catalogSnapshot?.catalogVersion)
        assertEquals(CatalogDialog.None, state.catalogDialog)
    }

    @Test
    fun explicitSnapshotRefreshClearsNotice() {
        val state =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(
                    management(notice = CatalogNotice("目录已变化，请刷新", error = true), version = 1L),
                    P503UiEvent.CatalogSnapshotRefreshed(snapshot(version = 3L)),
                ),
            )
        assertEquals(3L, state.catalogSnapshot?.catalogVersion)
        assertNull(state.catalogNotice)
    }

    @Test
    fun refreshedReadStateInstallsTheNewNamesWithoutChangingIdsOrAmounts() {
        // R1 (spec 7.3/D-027, 6.2): after a rename the host re-queries the authoritative state
        // and dispatches the ordinary RefreshResult; HOME must then render the new name while
        // stable ids and amounts stay byte-for-byte unchanged.
        val renamedAccountId = accountId
        val oldName =
            LedgerCurrentState(
                ledgerId,
                transactions = emptyList<CurrentVersionRow>(),
                balances = listOf(AccountCurrencyBalance(renamedAccountId, cny, -3_580L, -3_580L)),
                accountNames = mapOf(renamedAccountId to "旧名"),
            )
        val newNameState = oldName.copy(accountNames = mapOf(renamedAccountId to "新名"))

        val before =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(management(), P503UiEvent.Back, P503UiEvent.RefreshResult(oldName)),
            )
        assertEquals(P503Tab.HOME, before.selectedTab)
        assertEquals("旧名", before.state.accountNames.getValue(renamedAccountId))

        val after = assertIs<P503AppState.OverviewEmpty>(reduceFrom(before, P503UiEvent.RefreshResult(newNameState)))
        assertEquals(P503Tab.HOME, after.selectedTab)
        assertEquals("新名", after.state.accountNames.getValue(renamedAccountId))
        assertEquals(before.state.balances, after.state.balances)
        assertEquals(before.state.transactions, after.state.transactions)
    }

    @Test
    fun refreshedReadStateKeepsTheTabDialogAndNotice() {
        // F3: the R1 read-state transition only replaces `state`; management-only fields and the
        // selected tab are preserved (the host dispatches this while still on the ACCOUNTS tab).
        val notice = CatalogNotice("已保存", error = false)
        val before = management(dialog = CatalogDialog.CreateAccount(nameText = "现金"), notice = notice)

        val after = assertIs<P503AppState.OverviewEmpty>(reduceFrom(before, P503UiEvent.RefreshResult(overview)))

        assertEquals(P503Tab.ACCOUNTS, after.selectedTab)
        assertEquals(CatalogDialog.CreateAccount(nameText = "现金"), after.catalogDialog)
        assertEquals(notice, after.catalogNotice)
        assertEquals(snapshot(), after.catalogSnapshot)
        assertEquals(overview, after.state)
    }

    @Test
    fun activeToggleIntentIsAbsorbedUntilTheCommandResultArrives() {
        val before = management()
        val after = reduceFrom(before, P503UiEvent.ManageAccountActive(accountId, active = false))
        assertEquals(before, after)

        val settled =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(after, P503UiEvent.CatalogCommandCompleted(accepted(), snapshot(version = 2L))),
            )
        assertEquals(2L, settled.catalogSnapshot?.catalogVersion)
    }

    @Test
    fun groupEnableIntentIsAbsorbedUntilTheCommandResultArrives() {
        // C-8: "整组启用" is its own command (EnableCategoryGroup), executed by the host; the pure
        // reducer only absorbs the intent and settles when the typed result arrives.
        val before = management()
        val after = reduceFrom(before, P503UiEvent.EnableCategoryGroup(groupId))
        assertEquals(before, after)
        assertEquals(P503Tab.ACCOUNTS, assertIs<P503AppState.OverviewEmpty>(after).selectedTab)

        val settled =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(after, P503UiEvent.CatalogCommandCompleted(accepted(), snapshot(version = 4L))),
            )
        assertEquals(4L, settled.catalogSnapshot?.catalogVersion)
        assertEquals(CatalogNotice("已保存", error = false), settled.catalogNotice)
    }

    @Test
    fun backClosesOpenDialogBeforeLeavingTheAccountsTab() {
        val withDialog =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(management(), P503UiEvent.OpenAccountCreateDialog(), P503UiEvent.Back),
            )
        assertEquals(CatalogDialog.None, withDialog.catalogDialog)
        assertEquals(P503Tab.ACCOUNTS, withDialog.selectedTab)

        val home = assertIs<P503AppState.OverviewEmpty>(reduceFrom(withDialog, P503UiEvent.Back))
        assertEquals(P503Tab.HOME, home.selectedTab)
        assertEquals(overview, home.state)
    }

    @Test
    fun backOnPlainHomeOverviewStillFailsFast() {
        assertFailsWith<IllegalStateException> {
            reduceFrom(P503AppState.OverviewEmpty(overview, P503Tab.HOME), P503UiEvent.Back)
        }
    }

    @Test
    fun unrelatedEventInManagementStateFailsFast() {
        assertFailsWith<IllegalStateException> {
            reduceFrom(management(), P503UiEvent.UpdateAmount("1"))
        }
    }

    @Test
    fun failureMessageCoversEveryStableCode() {
        CatalogFailureCode.entries.forEach { code ->
            assertTrue(catalogFailureMessage(code).isNotBlank(), "missing message for ${code.code}")
        }
        assertEquals("目录已变化，请刷新", catalogFailureMessage(CatalogFailureCode.CATALOG_VERSION_CONFLICT))
    }

    private fun accepted(): CatalogCommandResult =
        CatalogCommandResult.Accepted(
            CatalogCommandReceipt(CatalogRequestId("request-1"), CatalogReceiptOutcome.ACCEPTED, 2L),
        )
}
