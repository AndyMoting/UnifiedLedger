package com.unifiedledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.CategoryTreeView
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.application.TagMerchantAuthority
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CategoryKind

/**
 * P7-01.D shared management surface (spec section 7.1-7.2): the ACCOUNTS tab's manageable
 * accounts plus the shared two-level category tree for expenses and income. The screen is pure
 * presentation over the ACCOUNTS-tab [P503AppState.OverviewEmpty] management fields; every
 * interaction is forwarded as a [P503UiEvent], and form submission is delegated to [onSubmit] so
 * the host runs the command and the authoritative refresh. Hidden/system accounts never appear
 * (A-2, projection-guaranteed).
 *
 * Ledger-identity short patch (D-146 entry 12, DECISIONS.md:2805): the header discloses the
 * active ledger read-only (same 账本： copy family as the home tab) and carries an honest
 * disabled 切换账本 trigger — this batch has no ledger-list data source (no picker port / use
 * case / lastActiveLedgerId persistence), so the trigger is never presented as usable and never
 * faked as a live picker; the full multi-ledger selector stays a P7-05+ future batch.
 */
@Composable
fun P503CatalogManagementScreen(
    state: P503AppState.OverviewEmpty,
    onEvent: (P503UiEvent) -> Unit,
    onSubmit: (CatalogDialog) -> Unit,
    onRefresh: () -> Unit = {},
    // P7-08 08.C (R-222-11; open item 11): the tag/merchant authority the two new sections
    // render. `null` = not yet loaded / unwired: the sections present the honest 载入中
    // placeholder instead of an authoritative-looking empty catalog (the S2-2 discipline).
    tagMerchantAuthority: TagMerchantAuthority? = null,
) {
    val snapshot = state.catalogSnapshot
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("账户与分类管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("刷新目录") }
        }
        // Ledger-identity short patch (D-146 entry 12): read-only active-ledger disclosure in the
        // same 账本： copy family as the home tab, plus the honest disabled switch trigger — no
        // ledger-list data source exists this batch, so it stays visibly not-enabled with its
        // explanation instead of a fake picker or a dead unexplained button.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                catalogLedgerIdentityText(state.state.ledgerId),
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier.weight(1f).semantics {
                        contentDescription = catalogLedgerIdentityContentDescription(state.state.ledgerId)
                    },
            )
            TextButton(
                onClick = {},
                enabled = CATALOG_SWITCH_LEDGER_ENABLED,
                modifier =
                    Modifier.semantics {
                        contentDescription = CATALOG_SWITCH_LEDGER_CONTENT_DESCRIPTION
                    },
            ) {
                Text(CATALOG_SWITCH_LEDGER_BUTTON_TEXT)
            }
            Text(CATALOG_SWITCH_LEDGER_HONEST_SUFFIX_TEXT, style = MaterialTheme.typography.bodySmall)
        }
        state.catalogNotice?.let { notice ->
            CatalogNoticeBanner(notice) { onEvent(P503UiEvent.DismissCatalogNotice) }
        }
        if (snapshot == null) {
            Text(
                "正在读取目录，请点击“刷新目录”。",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        AccountsSection(
            accounts = snapshot.manageableAccounts,
            balances = state.state.balances.associateBy { it.accountId },
            pinnedTargets = state.pinnedTargets,
            ledgerId = state.state.ledgerId,
            onEvent = onEvent,
        )
        HorizontalDivider()
        CategoriesSection(
            categories = snapshot.categories,
            kind = CategoryKind.EXPENSE,
            title = "支出分类",
            pinnedTargets = state.pinnedTargets,
            ledgerId = state.state.ledgerId,
            onEvent = onEvent,
        )
        HorizontalDivider()
        CategoriesSection(
            categories = snapshot.categories,
            kind = CategoryKind.INCOME,
            title = "收入分类",
            pinnedTargets = state.pinnedTargets,
            ledgerId = state.state.ledgerId,
            onEvent = onEvent,
        )
        HorizontalDivider()
        TagMerchantSections(authority = tagMerchantAuthority, onEvent = onEvent)
        Spacer(Modifier.height(24.dp))
    }

    CatalogDialogHost(dialog = state.catalogDialog, onEvent = onEvent, onSubmit = onSubmit)
}

@Composable
private fun CatalogNoticeBanner(
    notice: CatalogNotice,
    onDismiss: () -> Unit,
) {
    val color = if (notice.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.message, color = color, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("知道了") }
    }
}

@Composable
private fun AccountsSection(
    accounts: List<ManageableAccountView>,
    balances: Map<AccountId, AccountCurrencyBalance>,
    pinnedTargets: Set<EntryPinTarget>,
    ledgerId: com.unifiedledger.domain.LedgerId,
    onEvent: (P503UiEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("账户", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(
                onClick = { onEvent(P503UiEvent.OpenAccountCreateDialog()) },
                modifier = Modifier.semantics { contentDescription = "新增账户" },
            ) {
                Text("新增账户")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (accounts.isEmpty()) {
            Text("还没有可管理的账户。", style = MaterialTheme.typography.bodyLarge)
        }
        accounts.forEach { account ->
            val balance = balances[account.accountId]
            val currency = balance?.currency ?: account.currency
            val amount = balance?.displayMinorUnits ?: 0L
            val pinned = EntryPinTarget.AccountTarget(ledgerId, account.accountId) in pinnedTargets
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "${account.name}（${accountKindText(account.kind)}）" +
                        if (account.active) "" else " 已停用",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${currency.code} ${formatMinorUnits(amount, currency.precision)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onEvent(P503UiEvent.OpenAccountRenameDialog(account.accountId, account.name)) },
                    ) {
                        Text("改名")
                    }
                    TextButton(
                        onClick = {
                            onEvent(
                                P503UiEvent.ManageAccountActive(account.accountId, active = !account.active),
                            )
                        },
                    ) {
                        Text(if (account.active) "停用" else "启用")
                    }
                    // P7-02.D E-4: manual pin — ordering preference only; the host persists the
                    // toggle through the EntryPreferenceStore before the event is dispatched. The
                    // click requests the inverse of the rendered membership; the host replaces it
                    // with the store's authoritative result.
                    TextButton(
                        onClick = { onEvent(P503UiEvent.TogglePin(EntryPinTarget.AccountTarget(ledgerId, account.accountId), pinned = !pinned)) },
                    ) {
                        Text(if (pinned) "取消置顶" else "置顶")
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoriesSection(
    categories: List<CategoryTreeView>,
    kind: CategoryKind,
    title: String,
    pinnedTargets: Set<EntryPinTarget>,
    ledgerId: com.unifiedledger.domain.LedgerId,
    onEvent: (P503UiEvent) -> Unit,
) {
    val groups = categories.filter { it.kind == kind && it.parentId == null }
    val childrenByParent = categories.filter { it.kind == kind && it.parentId != null }.groupBy { checkNotNull(it.parentId) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(
                onClick = { onEvent(P503UiEvent.OpenCategoryGroupDialog(kind)) },
                modifier = Modifier.semantics { contentDescription = "新建一级分类" },
            ) {
                Text("新建一级分类")
            }
        }
        Spacer(Modifier.height(8.dp))
        groups.forEach { group ->
            GroupRow(group, pinnedTargets, ledgerId, onEvent)
            childrenByParent[group.categoryId].orEmpty().forEach { child ->
                LeafRow(groupName = group.name, child = child, pinnedTargets = pinnedTargets, ledgerId = ledgerId, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: CategoryTreeView,
    pinnedTargets: Set<EntryPinTarget>,
    ledgerId: com.unifiedledger.domain.LedgerId,
    onEvent: (P503UiEvent) -> Unit,
) {
    val pinned = EntryPinTarget.CategoryTarget(ledgerId, group.categoryId) in pinnedTargets
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            group.name + if (group.active) "" else " 已停用",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onEvent(P503UiEvent.OpenCategoryAppendChildDialog(group.categoryId)) }) {
                Text("追加二级")
            }
            TextButton(onClick = { onEvent(P503UiEvent.OpenCategoryRenameDialog(group.categoryId, group.name)) }) {
                Text("改名")
            }
            if (group.active) {
                TextButton(onClick = { onEvent(P503UiEvent.ManageCategoryActive(group.categoryId, active = false)) }) {
                    Text("停用")
                }
            } else {
                // C-8: "整组启用" reactivates the group and every child via EnableCategoryGroup;
                // a plain SetCategoryActive would only flip the parent row.
                TextButton(onClick = { onEvent(P503UiEvent.EnableCategoryGroup(group.categoryId)) }) {
                    Text("整组启用")
                }
            }
            TextButton(onClick = { onEvent(P503UiEvent.OpenCategoryDeleteDialog(group.categoryId)) }) {
                Text("删除")
            }
            TextButton(onClick = { onEvent(P503UiEvent.TogglePin(EntryPinTarget.CategoryTarget(ledgerId, group.categoryId), pinned = !pinned)) }) {
                Text(if (pinned) "取消置顶" else "置顶")
            }
        }
    }
}

@Composable
private fun LeafRow(
    groupName: String,
    child: CategoryTreeView,
    pinnedTargets: Set<EntryPinTarget>,
    ledgerId: com.unifiedledger.domain.LedgerId,
    onEvent: (P503UiEvent) -> Unit,
) {
    val pinned = EntryPinTarget.CategoryTarget(ledgerId, child.categoryId) in pinnedTargets
    Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, bottom = 4.dp)) {
        // D-024: level-2 names are disambiguated by their "一级 / 二级" path.
        Text(
            "$groupName / ${child.name}" + if (child.active) "" else " 已停用",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onEvent(P503UiEvent.OpenCategoryRenameDialog(child.categoryId, child.name)) }) {
                Text("改名")
            }
            TextButton(onClick = { onEvent(P503UiEvent.ManageCategoryActive(child.categoryId, active = !child.active)) }) {
                Text(if (child.active) "停用" else "启用")
            }
            TextButton(onClick = { onEvent(P503UiEvent.OpenCategoryDeleteDialog(child.categoryId)) }) {
                Text("删除")
            }
            TextButton(onClick = { onEvent(P503UiEvent.TogglePin(EntryPinTarget.CategoryTarget(ledgerId, child.categoryId), pinned = !pinned)) }) {
                Text(if (pinned) "取消置顶" else "置顶")
            }
        }
    }
}

/**
 * The section's ACTIONABLE rows: every non-tombstoned item (active and disabled — a disabled
 * item keeps its 改名/启用/删除 actions). Pure so the UI test pins the production split.
 */
internal fun tagMerchantLiveItems(items: List<CatalogItem>): List<CatalogItem> = items.filter { !it.tombstoned }

/**
 * The section's READ-ONLY audit rows (open item 11): the tombstoned items. They render in the
 * 已删除（审计记录） block with no rename/disable/delete affordance — the domain rejects every
 * command on them and the UI exposes no entry either. Pure so the UI test pins the split.
 */
internal fun tagMerchantAuditItems(items: List<CatalogItem>): List<CatalogItem> = items.filter { it.tombstoned }

/**
 * P7-08 08.C (R-222-11): the tag and merchant management sections. Each section lists its live
 * items (active and disabled — a disabled item keeps its 改名/启用/删除 actions) and renders the
 * TOMBSTONED items as a READ-ONLY audit block (the open-item-11 ruling): a tombstoned item
 * offers no rename/disable/delete affordance at all — the domain blocks every command on it and
 * the UI exposes no dead entry either.
 */
@Composable
private fun TagMerchantSections(
    authority: TagMerchantAuthority?,
    onEvent: (P503UiEvent) -> Unit,
) {
    if (authority == null) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("标签与商家", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("标签与商家目录载入中…", style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
        return
    }
    CatalogItemsSection(
        title = "标签",
        createText = "新建标签",
        items = authority.tags,
        onCreate = { onEvent(P503UiEvent.OpenTagCreateDialog) },
        onRename = { item -> onEvent(P503UiEvent.OpenTagRenameDialog(item.id, item.name)) },
        onToggleActive = { item -> onEvent(P503UiEvent.ManageTagActive(item.id, active = !item.active)) },
        onDelete = { item -> onEvent(P503UiEvent.OpenTagDeleteDialog(item.id, item.name)) },
    )
    HorizontalDivider()
    CatalogItemsSection(
        title = "商家",
        createText = "新建商家",
        items = authority.merchants,
        onCreate = { onEvent(P503UiEvent.OpenMerchantCreateDialog) },
        onRename = { item -> onEvent(P503UiEvent.OpenMerchantRenameDialog(item.id, item.name)) },
        onToggleActive = { item -> onEvent(P503UiEvent.ManageMerchantActive(item.id, active = !item.active)) },
        onDelete = { item -> onEvent(P503UiEvent.OpenMerchantDeleteDialog(item.id, item.name)) },
    )
}

@Composable
private fun CatalogItemsSection(
    title: String,
    createText: String,
    items: List<CatalogItem>,
    onCreate: () -> Unit,
    onRename: (CatalogItem) -> Unit,
    onToggleActive: (CatalogItem) -> Unit,
    onDelete: (CatalogItem) -> Unit,
) {
    val live = tagMerchantLiveItems(items)
    val tombstoned = tagMerchantAuditItems(items)
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onCreate, modifier = Modifier.semantics { contentDescription = createText }) {
                Text(createText)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (live.isEmpty() && tombstoned.isEmpty()) {
            Text("还没有任何条目。", style = MaterialTheme.typography.bodyLarge)
        }
        live.forEach { item ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(item.name + if (item.active) "" else " 已停用", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onRename(item) }) { Text("改名") }
                    TextButton(onClick = { onToggleActive(item) }) { Text(if (item.active) "停用" else "启用") }
                    TextButton(onClick = { onDelete(item) }) { Text("删除") }
                }
            }
        }
        if (tombstoned.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // The read-only tombstone audit block (open item 11): visible for audit, never
            // actionable — no rename/disable/delete affordance exists on these rows.
            Text("已删除（审计记录，不可恢复）", style = MaterialTheme.typography.titleSmall)
            tombstoned.forEach { item ->
                Text(
                    item.name + "（已删除）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CatalogDialogHost(
    dialog: CatalogDialog,
    onEvent: (P503UiEvent) -> Unit,
    onSubmit: (CatalogDialog) -> Unit,
) {
    when (dialog) {
        CatalogDialog.None -> Unit
        is CatalogDialog.CreateAccount ->
            CatalogFormDialog(
                title = "新增账户",
                confirmText = "创建",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Text("类型（币种固定 CNY，初始余额为 0）", style = MaterialTheme.typography.bodySmall)
                KindChoice(
                    label = "资产",
                    selected = dialog.kind == AccountKind.ASSET,
                    onSelect = { onEvent(P503UiEvent.UpdateCatalogFormKind(AccountKind.ASSET)) },
                )
                KindChoice(
                    label = "负债",
                    selected = dialog.kind == AccountKind.LIABILITY,
                    onSelect = { onEvent(P503UiEvent.UpdateCatalogFormKind(AccountKind.LIABILITY)) },
                )
            }
        is CatalogDialog.RenameAccount ->
            CatalogFormDialog(
                title = "账户改名",
                confirmText = "保存",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("新名称") },
                    singleLine = true,
                )
            }
        is CatalogDialog.CreateCategoryGroup ->
            CatalogFormDialog(
                title = "新建一级分类",
                confirmText = "创建",
                confirmEnabled = dialog.groupNameText.isNotBlank() && dialog.firstChildNameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.groupNameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("一级分类名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dialog.firstChildNameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormSecondaryText(it)) },
                    label = { Text("首个二级分类名称（必填）") },
                    singleLine = true,
                )
            }
        is CatalogDialog.AppendCategoryChild ->
            CatalogFormDialog(
                title = "追加二级分类",
                confirmText = "创建",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("二级分类名称") },
                    singleLine = true,
                )
            }
        is CatalogDialog.RenameCategory ->
            CatalogFormDialog(
                title = "分类改名",
                confirmText = "保存",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("新名称") },
                    singleLine = true,
                )
            }
        is CatalogDialog.ConfirmCategoryDelete ->
            AlertDialog(
                onDismissRequest = { onEvent(P503UiEvent.DismissCatalogDialog) },
                title = { Text("删除分类") },
                text = { Text("仅无任何引用的分类可以删除；已有引用时请改用停用。") },
                confirmButton = {
                    TextButton(onClick = { onSubmit(dialog) }) { Text("确认删除") }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(P503UiEvent.DismissCatalogDialog) }) { Text("取消") }
                },
            )
        // ---- P7-08 08.C tag/merchant dialogs (R-222-11) ----
        is CatalogDialog.CreateTagItem ->
            CatalogFormDialog(
                title = "新建标签",
                confirmText = "创建",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("名称") },
                    singleLine = true,
                )
            }
        is CatalogDialog.RenameTagItem ->
            CatalogFormDialog(
                title = "标签改名",
                confirmText = "保存",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("新名称") },
                    singleLine = true,
                )
            }
        is CatalogDialog.ConfirmTagItemDelete ->
            AlertDialog(
                onDismissRequest = { onEvent(P503UiEvent.DismissCatalogDialog) },
                title = { Text("删除标签") },
                text = { Text("仅无任何引用的标签可以删除；删除后不可恢复、名称不可再建。已有引用时请改用停用。") },
                confirmButton = {
                    TextButton(onClick = { onSubmit(dialog) }) { Text("确认删除") }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(P503UiEvent.DismissCatalogDialog) }) { Text("取消") }
                },
            )
        is CatalogDialog.CreateMerchantItem ->
            CatalogFormDialog(
                title = "新建商家",
                confirmText = "创建",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("名称") },
                    singleLine = true,
                )
            }
        is CatalogDialog.RenameMerchantItem ->
            CatalogFormDialog(
                title = "商家改名",
                confirmText = "保存",
                confirmEnabled = dialog.nameText.isNotBlank(),
                onDismiss = { onEvent(P503UiEvent.DismissCatalogDialog) },
                onConfirm = { onSubmit(dialog) },
            ) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = { onEvent(P503UiEvent.UpdateCatalogFormText(it)) },
                    label = { Text("新名称") },
                    singleLine = true,
                )
            }
        is CatalogDialog.ConfirmMerchantItemDelete ->
            AlertDialog(
                onDismissRequest = { onEvent(P503UiEvent.DismissCatalogDialog) },
                title = { Text("删除商家") },
                text = { Text("仅无任何引用的商家可以删除；删除后不可恢复、名称不可再建。已有引用时请改用停用。") },
                confirmButton = {
                    TextButton(onClick = { onSubmit(dialog) }) { Text("确认删除") }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(P503UiEvent.DismissCatalogDialog) }) { Text("取消") }
                },
            )
    }
}

@Composable
private fun CatalogFormDialog(
    title: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { content() } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun KindChoice(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
        Spacer(Modifier.width(4.dp))
    }
}

internal fun accountKindText(kind: AccountKind): String =
    when (kind) {
        AccountKind.ASSET -> "资产"
        AccountKind.LIABILITY -> "负债"
        AccountKind.EQUITY -> "权益"
        AccountKind.INCOME -> "收入"
        AccountKind.EXPENSE -> "支出"
    }

// ------------------------------------------------- ledger-identity short patch copy (D-146 entry 12)
// Pure presentation copy for the active-ledger disclosure and the honest disabled switch
// trigger; pinned verbatim by P503CatalogManagementPresentationTest. The full multi-ledger
// selector (picker port / use case / lastActiveLedgerId persistence) is a P7-05+ batch.

/** The read-only current-ledger line; same 账本： copy family as the home tab (P503OverviewScreen). */
internal fun catalogLedgerIdentityText(ledgerId: com.unifiedledger.domain.LedgerId): String = "账本：${ledgerId.value}"

/** The accessibility label of the ledger-identity line (C04 style: one announced node). */
internal fun catalogLedgerIdentityContentDescription(ledgerId: com.unifiedledger.domain.LedgerId): String = "当前账本：${ledgerId.value}"

/** Whether the switch trigger is usable: `false` this batch — no ledger-list data source exists. */
internal const val CATALOG_SWITCH_LEDGER_ENABLED: Boolean = false

/** The switch trigger's visible label. */
internal const val CATALOG_SWITCH_LEDGER_BUTTON_TEXT: String = "切换账本"

/**
 * The honest suffix next to the disabled trigger — same honesty class as an import format
 * matrix entry still pending device runtime verification: never presented as usable, never a
 * dead button without explanation (R-Q08-3).
 */
internal const val CATALOG_SWITCH_LEDGER_HONEST_SUFFIX_TEXT: String = "（多账本未启用）"

/** The accessibility label of the disabled switch trigger: states it is not enabled. */
internal const val CATALOG_SWITCH_LEDGER_CONTENT_DESCRIPTION: String = "切换账本（未启用）"
