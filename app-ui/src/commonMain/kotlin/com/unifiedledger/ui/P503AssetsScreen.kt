package com.unifiedledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.ui.theme.LocalP8Palette

/*
 * P8-00 b2 asset summary region (D-301; spec `docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md`
 * sections 2.2/4, acceptance vector V-4). The region rides above the ACCOUNTS tab's management
 * sections and renders the canvas-02 rhythm:
 *
 * 1. the net-worth card split into ONE BLOCK PER CURRENCY (spec section 4, frozen): each block is
 *    that currency's 「资产账户余额和 − 负债账户余额和」 big figure plus the asset/liability sub-lines.
 *    The arithmetic is exact integer minor units (Long addition/subtraction; never Double/Float);
 *    currencies are never merged, never converted, and no exchange rate exists anywhere; a
 *    currency with no holdings (no balance row) renders no block; a single-currency ledger
 *    degrades to one block. Balance rows whose account is not in the manageable projection
 *    (D-143: the hidden counterparty receivable accounts) are excluded — they would double-count
 *    against the LEND funding leg and they never enter the A-2 management surface.
 * 2. the 总借入/总借出 card pair with the canvas layout. The values are an honest placeholder
 *    (—), never a fabricated zero: no composable consumes the wired `lendingPositions` port
 *    (both composition roots pass it into the facade — desktop `Main.kt`, android `App.kt` —
 *    but nothing reads it), the per-counterparty/per-currency aggregation口径 is unruled, and
 *    the product lending chain (P7-02.C) has LEND/COLLECT behaviors only — a 借入 position
 *    source does not exist at all. Wiring exact values (总借出 over
 *    `LendingPositionReadPort`, 借入 pending a product source) belongs to a later batch.
 * 3. the grouped account lists 「资产 · N 个账户」/「负债 · N 个账户」 with rows = name + kind +
 *    balance. The data口径 is exactly the现行 [P503CatalogManagementScreen] account rows: the
 *    manageable projection × [LedgerCurrentState.balances], the balance row's currency (else the
 *    account's declared currency) and its display amount (else 0), snapshot order preserved.
 *
 * The grouping/aggregation itself lives in the pure functions below (asserted in commonTest);
 * the composables are thin renderers over them. The region is additive: the management surface
 * below it is unchanged. The pure aggregation functions and their test live with the frozen V-4
 * vector in `P8AssetScreenAggregationTest`.
 */

/** One per-currency net-worth block. `netMinor = assetMinor - liabilityMinor` exactly. */
internal data class P8AssetCurrencyBlock(
    val currency: CurrencyUnit,
    val assetMinor: Long,
    val liabilityMinor: Long,
) {
    val netMinor: Long
        get() = assetMinor - liabilityMinor
}

/** One grouped account row: the account plus the resolved display currency and amount. */
internal data class P8AssetAccountRow(
    val account: ManageableAccountView,
    val currency: CurrencyUnit,
    val minorUnits: Long,
)

/** The asset/liability grouping of the manageable accounts, snapshot order preserved. */
internal data class P8AssetAccountGroups(
    val assetRows: List<P8AssetAccountRow>,
    val liabilityRows: List<P8AssetAccountRow>,
)

/**
 * The display currency of one account, the same rule as the现行 account rows: the balance row's
 * authoritative currency, else the account's declared currency (a never-posted account).
 */
internal fun assetResolvedCurrency(
    account: ManageableAccountView,
    balancesByAccountId: Map<AccountId, AccountCurrencyBalance>,
): CurrencyUnit = balancesByAccountId[account.accountId]?.currency ?: account.currency

/**
 * The per-currency net-worth blocks (spec section 4, frozen). A currency enters the result only
 * through a BALANCE ROW of a manageable account (a row exists = the currency has holdings); the
 * row's account must be in the manageable projection or the row is skipped (the hidden receivable
 * accounts). Sums are exact Long additions over `displayMinorUnits`; block order follows the
 * projection's balance order (deterministic, no re-sorting).
 */
internal fun assetNetWorthBlocks(
    accounts: List<ManageableAccountView>,
    balances: List<AccountCurrencyBalance>,
): List<P8AssetCurrencyBlock> {
    val kindByAccountId: Map<AccountId, AccountKind> = accounts.associateBy({ it.accountId }, { it.kind })
    val sums = LinkedHashMap<CurrencyUnit, LongArray>()
    for (balance in balances) {
        val kind = kindByAccountId[balance.accountId] ?: continue
        val accumulator = sums.getOrPut(balance.currency) { LongArray(2) }
        when (kind) {
            AccountKind.ASSET -> accumulator[0] += balance.displayMinorUnits
            AccountKind.LIABILITY -> accumulator[1] += balance.displayMinorUnits
            // The manageable predicate admits ASSET/LIABILITY only; the other kinds stay a
            // defensive zero contribution.
            AccountKind.EQUITY,
            AccountKind.INCOME,
            AccountKind.EXPENSE,
            -> Unit
        }
    }
    return sums.map { (currency, accumulator) -> P8AssetCurrencyBlock(currency, accumulator[0], accumulator[1]) }
}

/**
 * The asset/liability grouped rows, the现行 account-row口径: display currency = balance row's
 * currency else the declared one; amount = the balance row's display amount else 0; snapshot
 * order preserved inside each group.
 */
internal fun assetAccountGroups(
    accounts: List<ManageableAccountView>,
    balancesByAccountId: Map<AccountId, AccountCurrencyBalance>,
): P8AssetAccountGroups {
    val rows =
        accounts.map { account ->
            P8AssetAccountRow(
                account = account,
                currency = assetResolvedCurrency(account, balancesByAccountId),
                minorUnits = balancesByAccountId[account.accountId]?.displayMinorUnits ?: 0L,
            )
        }
    return P8AssetAccountGroups(
        assetRows = rows.filter { it.account.kind == AccountKind.ASSET },
        liabilityRows = rows.filter { it.account.kind == AccountKind.LIABILITY },
    )
}

/**
 * The region renderer. An empty manageable projection renders nothing (the management surface's
 * 还没有可管理的账户 copy below stays the single empty voice); a ledger whose accounts have no
 * balance rows yet renders the card pair and the grouped lists without a net-worth card.
 *
 * P8-00 b3 (V-5): the account rows become the account-flow surface's entry (the only new
 * affordance this batch adds to the region). The open is host-wired via [onOpenAccountFlow];
 * an unwired host renders the same read-only rows b2 shipped (no dead click). The scroll SAMPLER
 * rides the same seam (the 返回保滚动位 mechanism — the ACCOUNTS list position is sampled
 * synchronously at click time and restored on return; the scroll state itself is owned by the
 * management surface's hoisted Column, not by this region).
 */
@Composable
internal fun P503AssetSummaryRegion(
    state: P503AppState.OverviewEmpty,
    onOpenAccountFlow: ((AccountId) -> Unit)? = null,
    sampleListScroll: (() -> ListScrollPosition?)? = null,
) {
    val snapshot = state.catalogSnapshot ?: return
    if (snapshot.manageableAccounts.isEmpty()) return
    val palette = LocalP8Palette.current
    val balances = state.state.balances
    val balancesByAccountId = remember(balances) { balances.associateBy { it.accountId } }
    val blocks = remember(snapshot, balances) { assetNetWorthBlocks(snapshot.manageableAccounts, balances) }
    val groups = remember(snapshot, balances) { assetAccountGroups(snapshot.manageableAccounts, balancesByAccountId) }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (blocks.isNotEmpty()) {
            Surface(
                color = palette.card,
                shape = RoundedCornerShape(palette.cardRadius),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text("净资产", style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                    blocks.forEachIndexed { index, block ->
                        if (index > 0) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = palette.divider)
                        }
                        Spacer(Modifier.height(10.dp))
                        val amountStyle =
                            if (index == 0) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge
                        Text(
                            "${block.currency.code} ${formatMinorUnits(block.netMinor, block.currency.precision)}",
                            style = amountStyle,
                            color = if (block.netMinor < 0L) palette.expense else palette.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                "资产 ${block.currency.code} " +
                                    formatMinorUnits(block.assetMinor, block.currency.precision),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.onSurfaceVariant,
                            )
                            Text(
                                "负债 ${block.currency.code} " +
                                    formatMinorUnits(block.liabilityMinor, block.currency.precision),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P8LendingSummaryCard("总借入", Modifier.weight(1f))
            P8LendingSummaryCard("总借出", Modifier.weight(1f))
        }
        P8AssetGroupSection("资产", groups.assetRows, onOpenAccountFlow, sampleListScroll)
        P8AssetGroupSection("负债", groups.liabilityRows, onOpenAccountFlow, sampleListScroll)
    }
}

/**
 * One lending summary card. The value is the honest placeholder dash — never a fabricated zero
 * (the widget-snapshot 不可用-over-zero precedent); see the file KDoc for why no exact value can
 * render yet.
 */
@Composable
private fun P8LendingSummaryCard(
    label: String,
    modifier: Modifier,
) {
    val palette = LocalP8Palette.current
    Surface(color = palette.card, shape = RoundedCornerShape(palette.cardRadius), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text("—", style = MaterialTheme.typography.titleMedium, color = palette.disabledFaint)
        }
    }
}

/**
 * One grouped list: the 「资产 · N 个账户」-style heading outside the card, then the account rows
 * separated by in-card dividers. P8-00 b3 (V-5): rows become the account-flow entry when the host
 * wires [onOpenAccountFlow] (the click samples the live scroll position synchronously — the
 * 返回保滚动位 mechanism); an unwired host keeps the b2 read-only rows.
 */
@Composable
private fun P8AssetGroupSection(
    title: String,
    rows: List<P8AssetAccountRow>,
    onOpenAccountFlow: ((AccountId) -> Unit)? = null,
    sampleListScroll: (() -> ListScrollPosition?)? = null,
) {
    if (rows.isEmpty()) return
    val palette = LocalP8Palette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$title · ${rows.size} 个账户",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = palette.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        Surface(
            color = palette.card,
            shape = RoundedCornerShape(palette.cardRadius),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(color = palette.divider, modifier = Modifier.padding(start = 16.dp))
                    }
                    P8AssetAccountLine(row, onOpenAccountFlow, sampleListScroll)
                }
            }
        }
    }
}

/**
 * One account row: name (已停用 disclosed like the现行 rows) + kind + exact balance. P8-00 b3
 * (V-5): when the host wires [onOpenAccountFlow] the row becomes the account-flow entry — the
 * click samples the ACCOUNTS list position BEFORE the open event (no async write-back hop), and
 * TalkBack announces the affordance through the click label while the row values stay reachable.
 */
@Composable
private fun P8AssetAccountLine(
    row: P8AssetAccountRow,
    onOpenAccountFlow: ((AccountId) -> Unit)? = null,
    sampleListScroll: (() -> ListScrollPosition?)? = null,
) {
    val palette = LocalP8Palette.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // V-8 (1.3x font): the row keeps 56 dp as a floor and grows with the scaled text,
                // so the single-line name never clips against a fixed band.
                .heightIn(min = 56.dp)
                .then(
                    if (onOpenAccountFlow != null) {
                        Modifier.clickable(onClickLabel = "查看账户流水") {
                            sampleListScroll?.invoke()
                            onOpenAccountFlow(row.account.accountId)
                        }
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.account.name + if (row.account.active) "" else " 已停用",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            accountKindText(row.account.kind),
            style = MaterialTheme.typography.bodySmall,
            color = palette.disabledFaint,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            "${row.currency.code} ${formatMinorUnits(row.minorUnits, row.currency.precision)}",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
