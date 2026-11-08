package com.unifiedledger.ui

import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P8-00 b2 (D-301; spec `2026-11-08-p8-00-visual-nav-baseline-design.md` sections 2.2/4,
 * acceptance vector V-4) pure aggregation tests for the asset screen: the per-currency net-worth
 * blocks and the grouped account lists. The frozen口径: exact integer minor units (Long), per
 * currency never merged/converted, a currency with no balance row renders no block, a
 * single-currency ledger degrades to one block, hidden non-manageable balance rows (the D-143
 * counterparty receivables) are excluded, and the account rows read the same现行口径 as
 * [P503CatalogManagementScreen] (balance row's currency else the declared one, display amount
 * else 0, snapshot order).
 */
class P8AssetScreenAggregationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)

    private fun account(
        id: String,
        kind: AccountKind,
        currency: CurrencyUnit = cny,
        name: String = id,
        active: Boolean = true,
    ): ManageableAccountView = ManageableAccountView(accountId = AccountId(id), name = name, kind = kind, currency = currency, active = active, balanceMinorUnits = null)

    private fun balance(
        accountId: String,
        currency: CurrencyUnit,
        displayMinor: Long,
    ): AccountCurrencyBalance = AccountCurrencyBalance(AccountId(accountId), currency, displayMinor, displayMinor)

    // ---- the frozen V-4 vector ----

    @Test
    fun aMultiCurrencyLedgerRendersOneBlockPerCurrencyWithExactSums() {
        val accounts =
            listOf(
                account("asset-bank", AccountKind.ASSET),
                account("asset-cash", AccountKind.ASSET),
                account("asset-usd", AccountKind.ASSET, usd),
                account("liab-card", AccountKind.LIABILITY),
                account("liab-usd", AccountKind.LIABILITY, usd),
            )
        val balances =
            listOf(
                balance("asset-bank", cny, 268_900_42L),
                balance("asset-cash", cny, 1_260_50L),
                balance("liab-card", cny, 2_380_75L),
                balance("asset-usd", usd, 1_000_00L),
                balance("liab-usd", usd, 238_00L),
            )
        val blocks = assetNetWorthBlocks(accounts, balances)
        assertEquals(2, blocks.size, "two currencies with holdings → exactly two blocks")
        val cnyBlock = blocks.first { it.currency == cny }
        val usdBlock = blocks.first { it.currency == usd }
        // Exact integer sums: 26890042 + 126050 = 27016092; 27016092 - 238075 = 26778017.
        assertEquals(27_016_092L, cnyBlock.assetMinor)
        assertEquals(2_380_75L, cnyBlock.liabilityMinor)
        assertEquals(26_778_017L, cnyBlock.netMinor)
        assertEquals(1_000_00L, usdBlock.assetMinor)
        assertEquals(238_00L, usdBlock.liabilityMinor)
        assertEquals(762_00L, usdBlock.netMinor)
    }

    @Test
    fun currenciesAreNeverMergedOrConverted() {
        val accounts = listOf(account("asset-cny", AccountKind.ASSET), account("asset-usd", AccountKind.ASSET, usd))
        val balances = listOf(balance("asset-cny", cny, 5_00L), balance("asset-usd", usd, 5_00L))
        val blocks = assetNetWorthBlocks(accounts, balances)
        // No merged block, no conversion: the two currencies stay separate blocks of their own sums.
        assertEquals(2, blocks.size)
        assertTrue(blocks.none { it.currency != cny && it.currency != usd })
        assertEquals(5_00L, blocks.first { it.currency == cny }.netMinor)
        assertEquals(5_00L, blocks.first { it.currency == usd }.netMinor)
    }

    @Test
    fun aCurrencyWithoutHoldingsRendersNoBlock() {
        // A declared USD account with NO balance row (never posted): no USD block appears.
        val accounts = listOf(account("asset-cny", AccountKind.ASSET), account("asset-usd", AccountKind.ASSET, usd))
        val balances = listOf(balance("asset-cny", cny, 7_00L))
        val blocks = assetNetWorthBlocks(accounts, balances)
        assertEquals(1, blocks.size)
        assertEquals(cny, blocks.single().currency)
        assertEquals(7_00L, blocks.single().netMinor)
    }

    @Test
    fun aSingleCurrencyLedgerDegradesToExactlyOneBlock() {
        val accounts = listOf(account("asset-only", AccountKind.ASSET), account("liab-only", AccountKind.LIABILITY))
        val balances = listOf(balance("asset-only", cny, 10_00L), balance("liab-only", cny, 4_00L))
        val blocks = assetNetWorthBlocks(accounts, balances)
        assertEquals(1, blocks.size)
        assertEquals(10_00L - 4_00L, blocks.single().netMinor)
    }

    @Test
    fun theSumsAreExactLongArithmeticNeverFloatingPoint() {
        // The classic binary-float trap: 0.1 + 0.2. Exact minor-unit Long math gives 30, never 29.999…
        val accounts = listOf(account("a1", AccountKind.ASSET), account("a2", AccountKind.ASSET))
        val balances = listOf(balance("a1", cny, 10L), balance("a2", cny, 20L))
        val block = assetNetWorthBlocks(accounts, balances).single()
        assertEquals(30L, block.netMinor)
    }

    @Test
    fun negativeNetWorthCarriesTheExactNegativeSum() {
        val accounts = listOf(account("asset-x", AccountKind.ASSET), account("liab-y", AccountKind.LIABILITY))
        val balances = listOf(balance("asset-x", cny, 1_00L), balance("liab-y", cny, 9_00L))
        val block = assetNetWorthBlocks(accounts, balances).single()
        assertEquals(-8_00L, block.netMinor)
        assertEquals(1_00L, block.assetMinor)
        assertEquals(9_00L, block.liabilityMinor)
    }

    @Test
    fun hiddenNonManageableBalanceRowsAreExcludedFromEverySum() {
        // D-143: the counterparty receivable account holds a balance row but fails the A-2
        // manageable predicate; it must never enter the net-worth sums (it would double-count
        // the LEND principal against the funding leg).
        val accounts = listOf(account("asset-bank", AccountKind.ASSET))
        val balances =
            listOf(
                balance("asset-bank", cny, 10_00L),
                balance("counterparty-receivable", cny, 50_00L),
            )
        val block = assetNetWorthBlocks(accounts, balances).single()
        assertEquals(10_00L, block.assetMinor)
        assertEquals(10_00L, block.netMinor)
    }

    @Test
    fun zeroBalancesStillRenderTheirCurrencyBlock() {
        // A zero display balance IS a holding (the account was posted); the block renders with
        // the exact zero. Only a MISSING row suppresses a currency.
        val accounts = listOf(account("asset-zero", AccountKind.ASSET))
        val balances = listOf(balance("asset-zero", cny, 0L))
        val block = assetNetWorthBlocks(accounts, balances).single()
        assertEquals(cny, block.currency)
        assertEquals(0L, block.netMinor)
    }

    @Test
    fun blockOrderFollowsTheProjectionBalanceOrder() {
        val accounts =
            listOf(account("asset-usd", AccountKind.ASSET, usd), account("asset-cny", AccountKind.ASSET))
        val balances = listOf(balance("asset-usd", usd, 2_00L), balance("asset-cny", cny, 3_00L))
        val blocks = assetNetWorthBlocks(accounts, balances)
        assertEquals(listOf(usd, cny), blocks.map { it.currency })
    }

    // ---- the grouped account rows (the现行口径) ----

    @Test
    fun groupedRowsFollowTheCatalogManagementAccountRowConvention() {
        val accounts =
            listOf(
                account("asset-first", AccountKind.ASSET),
                account("liab-card", AccountKind.LIABILITY),
                account("asset-inactive", AccountKind.ASSET, active = false),
                account("asset-unposted", AccountKind.ASSET),
            )
        val balances =
            listOf(
                balance("asset-first", cny, 12_34L),
                balance("liab-card", cny, 56_78L),
                // The inactive account keeps its row (the现行 rows render it with 已停用).
                balance("asset-inactive", cny, 1_00L),
            )
        val balancesByAccountId = balances.associateBy { it.accountId }
        val groups = assetAccountGroups(accounts, balancesByAccountId)
        assertEquals(3, groups.assetRows.size)
        assertEquals(1, groups.liabilityRows.size)
        // Snapshot order preserved inside each group.
        assertEquals(listOf("asset-first", "asset-inactive", "asset-unposted"), groups.assetRows.map { it.account.accountId.value })
        // Amount = balance row's display amount else 0 (the never-posted account).
        assertEquals(12_34L, groups.assetRows[0].minorUnits)
        assertEquals(1_00L, groups.assetRows[1].minorUnits)
        assertEquals(0L, groups.assetRows[2].minorUnits)
        // Currency = the balance row's authoritative currency else the declared one.
        assertEquals(cny, groups.assetRows[0].currency)
        assertEquals(cny, groups.assetRows[2].currency)
    }

    @Test
    fun theRowCurrencyIsTheBalanceRowCurrencyEvenIfTheDeclarationDiffers() {
        val accounts = listOf(account("asset-x", AccountKind.ASSET, usd))
        val balancesByAccountId = mapOf(AccountId("asset-x") to balance("asset-x", cny, 5_00L))
        val groups = assetAccountGroups(accounts, balancesByAccountId)
        assertEquals(cny, groups.assetRows.single().currency, "the authoritative balance currency wins")
        assertEquals(5_00L, groups.assetRows.single().minorUnits)
    }
}
