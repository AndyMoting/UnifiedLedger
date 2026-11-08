package com.unifiedledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.ui.theme.LocalP8Palette
import com.unifiedledger.ui.theme.glass.GlassBackdropSource
import com.unifiedledger.ui.theme.glass.GlassSurface
import com.unifiedledger.ui.theme.glass.rememberGlassBackdrop

/**
 * P8-00 shell (D-301; spec sections 1.1/2.1/3): the overview scaffold whose bottom bar is the
 * canvas's capsule dock with THREE tab items (HOME/ACCOUNTS/ANALYSIS — the D-146 fourth IMPORT
 * tab left the bottom bar; the import surface is the state-internal
 * [P503AppState.OverviewEmpty.importSurface] route) plus the independent circular record-plus
 * button to the right of the dock. The plus button shares the dock color family (dock fill +
 * stroke outline, white cross) and never upgrades to the accent color — the blend with the dock
 * is the frozen deliberate choice that covers the iCost blue-plus scheme. Selection lives in the
 * shared reducer state ([P503AppState.OverviewEmpty.selectedTab]); [selectedTab] is `null` while
 * the import surface renders (no bottom-bar item claims selection). P7-02.D E-2: when the host
 * passes [onSaveAndRecordAgain] (HOME tab with a retained determinate-success intent) a "record
 * again" button joins the row. P8-00: the optional [drawerState]/[drawerContent] pair wraps the
 * scaffold in the left ☰ drawer (spec section 2.5) and the optional [topBar] slot hosts the
 * screen header (the ☰ affordance and the HOME ledger picker).
 */
@Composable
fun P503TabShell(
    selectedTab: P503Tab?,
    onSelectTab: (P503Tab) -> Unit,
    onStartNewExpense: () -> Unit,
    onSaveAndRecordAgain: (() -> Unit)? = null,
    topBar: (@Composable () -> Unit)? = null,
    drawerState: DrawerState? = null,
    drawerContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val glassBackdrop = rememberGlassBackdrop()
    val palette = LocalP8Palette.current
    val scaffold: @Composable () -> Unit = {
        Scaffold(
            containerColor = palette.page,
            topBar = { topBar?.invoke() },
            bottomBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassSurface(
                        backdrop = glassBackdrop,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(palette.dockRadius),
                        fallbackColor = palette.dock,
                        border = BorderStroke(1.dp, palette.stroke),
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            windowInsets = WindowInsets(0.dp),
                        ) {
                            P8TabItem(selectedTab, P503Tab.HOME, "首", "首页", onSelectTab)
                            P8TabItem(selectedTab, P503Tab.ACCOUNTS, "账", "账户", onSelectTab)
                            P8TabItem(selectedTab, P503Tab.ANALYSIS, "析", "分析", onSelectTab)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    // The independent record-plus: dock fill + stroke, white cross; never the accent
                    // color (spec section 3 — the deliberate blend with the dock covers the iCost
                    // blue-plus scheme). F-Q2 fix round: the capsule beside this button consumes the
                    // same dock/stroke tokens (the stroke on both render paths, the dock fill on the
                    // glass-fallback path), so the family blend holds token-wise on either path.
                    Surface(
                        onClick = onStartNewExpense,
                        shape = RoundedCornerShape(percent = 50),
                        color = palette.dock,
                        border = BorderStroke(1.dp, palette.stroke),
                        shadowElevation = 4.dp,
                        modifier =
                            Modifier
                                .size(56.dp)
                                .semantics { contentDescription = "新增支出" },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.size(20.dp).align(Alignment.Center)) {
                                val stroke = 2.dp.toPx()
                                val half = size.minDimension / 2
                                drawLine(
                                    color = palette.onSurface,
                                    start = Offset(half, stroke),
                                    end = Offset(half, size.height - stroke),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    color = palette.onSurface,
                                    start = Offset(stroke, half),
                                    end = Offset(size.width - stroke, half),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            GlassBackdropSource(
                backdrop = glassBackdrop,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                content()
            }
        }
    }
    if (drawerState != null && drawerContent != null) {
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = drawerContent) {
            scaffold()
        }
    } else {
        scaffold()
    }
}

/** One dock tab item: the frozen character-glyph icon family with the canvas selection pill. */
@Composable
private fun RowScope.P8TabItem(
    selectedTab: P503Tab?,
    tab: P503Tab,
    glyph: String,
    label: String,
    onSelectTab: (P503Tab) -> Unit,
) {
    val palette = LocalP8Palette.current
    val selected = selectedTab == tab
    NavigationBarItem(
        selected = selected,
        onClick = { onSelectTab(tab) },
        icon = { Text(glyph) },
        label = { Text(label) },
        colors =
            NavigationBarItemDefaults.colors(
                selectedIconColor = palette.accent,
                selectedTextColor = palette.accent,
                unselectedIconColor = palette.onSurfaceVariant,
                unselectedTextColor = palette.onSurfaceVariant,
                indicatorColor = palette.accentSelectedBackground,
            ),
    )
}

/**
 * P8-00 shell (D-301; spec sections 1.3/2.1): the HOME header — the left ☰ drawer affordance and
 * the frozen ledger-picker form: the current ledger name with the ⌄ indicator as ONE clickable
 * title that routes to the target page. No in-place DropdownMenu is rendered anywhere (the frozen
 * V-3 shape: 顶栏账本名＋⌄点击进独立选择页，原地无下拉). The minimal target the shell batch wires is
 * the existing ACCOUNTS management surface (registered in the batch report; the standalone picker
 * page belongs to the ledger program). The ☰ and ⌄ marks are Canvas vectors — no emoji, no bitmap.
 */
@Composable
internal fun P8HomeTopBar(
    ledgerName: String,
    onOpenDrawer: () -> Unit,
    onOpenLedgerPicker: () -> Unit,
) {
    val palette = LocalP8Palette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onOpenDrawer,
            color = Color.Transparent,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.size(48.dp).semantics { contentDescription = "打开菜单" },
        ) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.size(20.dp).align(Alignment.Center)) {
                    val stroke = 2.dp.toPx()
                    val rows = listOf(0.5f, 1.5f, 2.5f)
                    rows.forEach { row ->
                        val y = stroke + (size.height - 2 * stroke) * (row / 3f)
                        drawLine(
                            color = palette.onSurface,
                            start = Offset(stroke, y),
                            end = Offset(size.width - stroke, y),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        Surface(
            onClick = onOpenLedgerPicker,
            color = Color.Transparent,
            shape = RoundedCornerShape(percent = 50),
            modifier =
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { contentDescription = "当前账本 $ledgerName，点按切换" },
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    ledgerName,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Canvas(Modifier.size(width = 12.dp, height = 6.dp).padding(start = 4.dp)) {
                    val stroke = 1.5.dp.toPx()
                    drawLine(
                        color = palette.onSurfaceVariant,
                        start = Offset(stroke, stroke),
                        end = Offset(size.width - stroke, stroke),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = palette.onSurfaceVariant,
                        start = Offset(stroke, stroke),
                        end = Offset(size.width / 2, size.height - stroke),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = palette.onSurfaceVariant,
                        start = Offset(size.width / 2, size.height - stroke),
                        end = Offset(size.width - stroke, stroke),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Spacer(Modifier.width(48.dp))
    }
}

/**
 * P8-00 shell (D-301; spec section 2.5): the left drawer's 「账本与数据」 sheet — the 「账本」
 * group (账本管理 → the existing ACCOUNTS management surface; 新建账本 as the frozen placeholder
 * entry whose behavior belongs to the ledger program) and the 「数据」 group (备份与恢复 → the
 * existing backup/restore surfaces when wired; 回收站 → the existing bin when wired; 导入账单 →
 * the state-internal import surface, V-2). Unwired optional surfaces render the honest disabled row
 * with the 暂未开放 suffix (the D-146 entry 12 disabled-trigger precedent) instead of a dead
 * button. Rows are plain text rows (no icon glyphs — no emoji, no bitmap; the Material Symbols
 * artifact is not among the current dependencies, registered in the batch report). [onClose]
 * runs the sheet-close action (the ✕ row / the header close button's host channel).
 *
 * P8-00 b2 (2026-11-08 user ruling): the 备份与恢复 row is the restore entry's CARRIER — its
 * target surface is the existing backup/export face whose state-internal restore route
 * ([P503UiEvent.OpenBackupRestore]) the reducer also accepts. [restoreWired] gates the restore
 * leg; when it is wired the row carries the 导出 · 恢复备份 suffix (the ruling's行尾标注) and
 * the click opens the carrier face; when unwired the row renders the 暂未开放 disabled shape.
 * The restore flow itself (preflight/confirm switch) is untouched — this is a pure entry
 * migration off the HOME overview.
 */
@Composable
internal fun P8LedgerDataDrawer(
    ledgerTitle: String,
    onOpenLedgerManagement: () -> Unit,
    ledgerManagementWired: Boolean,
    onOpenNewLedger: (() -> Unit)?,
    onOpenBackupRestore: () -> Unit,
    backupRestoreWired: Boolean,
    restoreWired: Boolean = false,
    onOpenRecycleBin: () -> Unit,
    recycleBinWired: Boolean,
    onOpenImportBills: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalP8Palette.current
    ModalDrawerSheet(drawerContainerColor = palette.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            Text("账本与数据", style = MaterialTheme.typography.titleLarge, color = palette.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(ledgerTitle, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            P8DrawerGroupHeader("账本")
            P8DrawerRow("账本管理", enabled = ledgerManagementWired, onClick = onOpenLedgerManagement)
            // F-Q7 fix round: the disabled placeholder row can never receive a click, so its onClick
            // is a no-op instead of the dead onClose wiring; the callback param stays for the ledger
            // batch that will wire 新建账本 (enabled = true + the real callback).
            P8DrawerRow("新建账本", enabled = false, suffix = "专项另行设计", onClick = {})
            P8DrawerGroupHeader("数据")
            // P8-00 b2: the carrier row. Wired = clickable, suffix 导出 · 恢复备份 (the ruling's
            // end-of-row annotation); unwired = the honest disabled 暂未开放 shape (the shell
            // batch precedent). The suffix rides the disabledFaint/variant colors of the row.
            P8DrawerRow(
                "备份与恢复",
                enabled = backupRestoreWired,
                suffix =
                    when {
                        backupRestoreWired && restoreWired -> "导出 · 恢复备份"
                        backupRestoreWired -> "导出"
                        else -> "暂未开放"
                    },
                onClick = onOpenBackupRestore,
            )
            P8DrawerRow("回收站", enabled = recycleBinWired, suffix = if (recycleBinWired) null else "暂未开放", onClick = onOpenRecycleBin)
            P8DrawerRow("导入账单", enabled = true, onClick = onOpenImportBills)
            Spacer(Modifier.height(16.dp))
            P8DrawerRow("关闭菜单", enabled = true, onClick = onClose)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A drawer group heading. */
@Composable
private fun P8DrawerGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = LocalP8Palette.current.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * One drawer row: a 48dp+ touch target (the minimumInteractiveComponentSize semantics via the
 * height floor), the frozen label, and the honest disabled state with its suffix.
 */
@Composable
private fun P8DrawerRow(
    label: String,
    enabled: Boolean,
    suffix: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalP8Palette.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) palette.onSurface else palette.disabledFaint)
            suffix?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(color = LocalP8Palette.current.divider)
}

/**
 * Accounts tab content is now the P7-01.D management surface ([P503CatalogManagementScreen] on
 * the shared [P503AppState.OverviewEmpty] management fields): manageable accounts with display
 * names, kind, balance and active flag, plus the shared two-level category tree. The former
 * read-only `P503AccountsScreen` (bare `accountId` rows) is superseded and removed.
 *
 * Analysis tab content (D-122): the pure [SummarizeLedgerActivity] derivation over the
 * authoritative current state, rendered as-is. The UI never accumulates amounts itself;
 * signed totals (which can be negative) go through [formatMinorUnits] unchanged.
 *
 * P7-03.D (D-145): when the composition root wires the ledger-view surface ([showMonthlyRegion])
 * the whole-period summary keeps its frozen D-122 face and the monthly region renders below it
 * (spec 6.1 layering): the month card and category drilldown for the shared month cursor, plus
 * the twelve-month trend (R-Q07-1) with the AnalysisMonthShift affordance. Both tabs consume the
 * same [com.unifiedledger.application.QueryMonthlyActivity] result (plan section 5.1).
 *
 * F4 (spec section 8 R-5): the special-kind divergence between the whole-period summary above and
 * the monthly ordinary totals is disclosed as a single special-kind count line next to the
 * category region, never mixed into the ordinary rows. F10 (P703SPEC-09): ordinary postings
 * without a category mapping appear as the explicit 无分类 row, so Σ分类 reconciles with the month
 * card. F2: [monthlyReloadRequired] swaps the empty-month copy for the explicit re-select
 * affordance after a READ-retry recovery. [interactionsEnabled] is `false` only on the retained
 * read-failure surface, where the analysis shift is absorbed (F1). G1: while
 * [monthlyReloadRequired] the category region and the trend region say explicitly that the monthly
 * data is not loaded instead of rendering an empty or stale surface (R-Q06-4).
 */
@Composable
fun P503AnalysisScreen(
    state: LedgerCurrentState,
    summarizeActivity: SummarizeLedgerActivity,
    showMonthlyRegion: Boolean = false,
    selectedMonth: kotlinx.datetime.YearMonth? = null,
    resolvedCurrentMonth: kotlinx.datetime.YearMonth? = null,
    selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
    monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    monthlyReloadRequired: Boolean = false,
    trend: com.unifiedledger.application.MonthlyTrend? = null,
    onSelectMonth: (kotlinx.datetime.YearMonth) -> Unit = {},
    onAnalysisMonthShift: (Int) -> Unit = {},
    interactionsEnabled: Boolean = true,
    // P7-07 07.D (D-184; spec section 5): the budget region slot. The host passes a non-null
    // slot only when the budget surface is wired (no dead region); the region owns its own
    // loading/failure copy, so a monthly not-loaded state does not mask it. Null keeps every
    // pre-P7-07 call site compiling.
    budgetRegion: (@Composable () -> Unit)? = null,
) {
    // DEF-B (D-268): the Analysis tab summarizes the exact SQL aggregates carried by [state];
    // the bounded `state.transactions` projection is never summarized as if it were the ledger.
    val summary = remember(state, summarizeActivity) { summarizeActivity.summarizeAggregate(state) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        if (summary.totalTransactionCount == 0) {
            Text("账本为空，还没有任何交易。", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("交易总笔数：${summary.totalTransactionCount}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("按交易类型", style = MaterialTheme.typography.bodyMedium)
            summary.countByKind.forEach { (kind, count) ->
                Text(
                    "$kind：$count",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("按币种收支", style = MaterialTheme.typography.bodyMedium)
            summary.totalsByCurrency.forEach { total ->
                Text(
                    "${total.currency.code}：" +
                        "支出 " + formatMinorUnits(total.expenseMinorUnits, total.currency.precision) +
                        "，收入 " + formatMinorUnits(total.incomeMinorUnits, total.currency.precision),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (showMonthlyRegion) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            P503MonthCard(
                activity = monthlyActivity,
                reloadRequired = monthlyReloadRequired,
                onReloadMonth =
                    if (monthlyReloadRequired) {
                        (selectedMonth ?: resolvedCurrentMonth)?.let { month -> { onSelectMonth(month) } }
                    } else {
                        null
                    },
            )
            Spacer(Modifier.height(4.dp))
            P503MonthSelector(selectedMonth, resolvedCurrentMonth, selectableMonths, onSelectMonth)
            // R-5 disclosure: the special-kind count line explains why this month's ordinary
            // totals need not equal the whole-period figures above.
            specialKindDisclosure(monthlyActivity)?.let { disclosure ->
                Spacer(Modifier.height(8.dp))
                Text(disclosure, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            P503CategoryRegion(
                title = "支出分类",
                categories = monthlyActivity?.expenseCategories ?: emptyList(),
                withPie = true,
                uncategorized = monthlyActivity?.uncategorizedExpenseTotals ?: emptyList(),
                reloadRequired = monthlyReloadRequired,
            )
            Spacer(Modifier.height(8.dp))
            P503CategoryRegion(
                title = "收入分类",
                categories = monthlyActivity?.incomeCategories ?: emptyList(),
                withPie = false,
                uncategorized = monthlyActivity?.uncategorizedIncomeTotals ?: emptyList(),
                reloadRequired = monthlyReloadRequired,
            )
            // P7-07 07.D: the additive budget region rides between the category regions and the
            // trend; it owns its own state copy (loaded/failure/unconfigured) independently of
            // the monthly cycle's state.
            budgetRegion?.invoke()
            Spacer(Modifier.height(8.dp))
            P503MonthlyTrendRegion(
                trend = trend,
                onAnalysisMonthShift = onAnalysisMonthShift,
                interactionsEnabled = interactionsEnabled,
                reloadRequired = monthlyReloadRequired,
                selectedMonth = selectedMonth,
                resolvedCurrentMonth = resolvedCurrentMonth,
                selectableMonths = selectableMonths,
            )
        }
    }
}
