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
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryKind

/**
 * P7-01.D shared management surface (spec section 7.1-7.2): the ACCOUNTS tab's manageable
 * accounts plus the shared two-level category tree for expenses and income. The screen is pure
 * presentation over the ACCOUNTS-tab [P503AppState.OverviewEmpty] management fields; every
 * interaction is forwarded as a [P503UiEvent], and form submission is delegated to [onSubmit] so
 * the host runs the command and the authoritative refresh. Hidden/system accounts never appear
 * (A-2, projection-guaranteed).
 */
@Composable
fun P503CatalogManagementScreen(
    state: P503AppState.OverviewEmpty,
    onEvent: (P503UiEvent) -> Unit,
    onSubmit: (CatalogDialog) -> Unit,
    onRefresh: () -> Unit = {},
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
            onEvent = onEvent,
        )
        HorizontalDivider()
        CategoriesSection(
            categories = snapshot.categories,
            kind = CategoryKind.EXPENSE,
            title = "支出分类",
            onEvent = onEvent,
        )
        HorizontalDivider()
        CategoriesSection(
            categories = snapshot.categories,
            kind = CategoryKind.INCOME,
            title = "收入分类",
            onEvent = onEvent,
        )
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
            GroupRow(group, onEvent)
            childrenByParent[group.categoryId].orEmpty().forEach { child ->
                LeafRow(groupName = group.name, child = child, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: CategoryTreeView,
    onEvent: (P503UiEvent) -> Unit,
) {
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
        }
    }
}

@Composable
private fun LeafRow(
    groupName: String,
    child: CategoryTreeView,
    onEvent: (P503UiEvent) -> Unit,
) {
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
