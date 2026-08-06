package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class BalanceAdjustmentTest {
    private val ledgerId = LedgerId("ledger-rg09")
    private val cny = CurrencyUnit("CNY", 2)
    private val target = AccountId("asset-a")
    private val equity = AccountId("equity-balance-adjustments")
    private val catalog = catalog()

    @Test
    fun `positive adjustment creates target increase and dedicated equity counterpart`() {
        val adjustment = assertIs<DomainResult.Success<BalanceAdjustment>>(
            createBalanceAdjustment(
                catalog,
                command(delta = Money.ofMinor(3_000L, cny)),
                ids(TransactionKind.BALANCE_ADJUSTMENT),
            ),
        ).value

        assertEquals(TransactionKind.BALANCE_ADJUSTMENT, adjustment.formalTransaction.transaction.kind)
        assertEquals(
            listOf(
                Triple(target.value, 3_000L, BalanceAdjustmentPostingRole.TARGET),
                Triple(equity.value, -3_000L, BalanceAdjustmentPostingRole.ADJUSTMENT_EQUITY),
            ),
            adjustment.postings.map { Triple(it.posting.accountId.value, it.posting.amount.minorUnits, it.role) },
        )
        assertEquals(
            BalanceAdjustmentReportEffects(3_000L, 3_000L),
            adjustment.reportEffects,
        )
    }

    @Test
    fun `reversal preserves signed direction and uses reversal transaction kind`() {
        val adjustment = assertIs<DomainResult.Success<BalanceAdjustment>>(
            createBalanceAdjustment(
                catalog,
                command(
                    delta = Money.ofMinor(-2_000L, cny),
                    kind = TransactionKind.BALANCE_ADJUSTMENT_REVERSAL,
                ),
                ids(TransactionKind.BALANCE_ADJUSTMENT_REVERSAL),
            ),
        ).value

        assertEquals(TransactionKind.BALANCE_ADJUSTMENT_REVERSAL, adjustment.formalTransaction.transaction.kind)
        assertEquals(-2_000L, adjustment.postings.first().posting.amount.minorUnits)
        assertEquals(2_000L, adjustment.postings.last().posting.amount.minorUnits)
        assertEquals(-2_000L, adjustment.reportEffects.netWorthChangeMinor)
    }

    @Test
    fun `validation rejects zero amount and non-dedicated equity before creating formal state`() {
        assertEquals(
            BalanceAdjustmentViolation.NonZeroAmountRequired,
            failure(createBalanceAdjustment(catalog, command(Money.ofMinor(0L, cny)), ids(TransactionKind.BALANCE_ADJUSTMENT))),
        )
        val wrongEquity = AccountId("equity-opening")
        assertEquals(
            BalanceAdjustmentViolation.DedicatedAdjustmentEquityRequired,
            failure(
                createBalanceAdjustment(
                    catalog,
                    command(Money.ofMinor(3_000L, cny), adjustmentEquityAccountId = wrongEquity),
                    ids(TransactionKind.BALANCE_ADJUSTMENT),
                ),
            ),
        )
    }

    private fun command(
        delta: Money,
        adjustmentEquityAccountId: AccountId = equity,
        kind: TransactionKind = TransactionKind.BALANCE_ADJUSTMENT,
    ) = BalanceAdjustmentCommand(
        ledgerId = ledgerId,
        targetAccountId = target,
        adjustmentEquityAccountId = adjustmentEquityAccountId,
        delta = delta,
        times = TransactionTimes.collapsed(Instant.parse("2026-01-31T15:59:59Z")),
        kind = kind,
    )

    private fun ids(kind: TransactionKind) = BalanceAdjustmentIds(
        transactionId = TransactionId("tx-${kind.name.lowercase()}"),
        versionId = TransactionVersionId("version-${kind.name.lowercase()}-v1"),
        postingSetId = PostingSetId("posting-set-${kind.name.lowercase()}"),
        targetPostingId = PostingId("posting-target-${kind.name.lowercase()}"),
        equityPostingId = PostingId("posting-equity-${kind.name.lowercase()}"),
    )

    private fun catalog(): LedgerCatalog = success(
        LedgerCatalog.create(
            accounts = listOf(
                Account(target, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("asset-b"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(
                    equity,
                    ledgerId,
                    AccountKind.EQUITY,
                    cny,
                    ownedByUser = false,
                    realAccount = false,
                    systemRole = BALANCE_ADJUSTMENT_EQUITY_ROLE,
                ),
                Account(AccountId("equity-opening"), ledgerId, AccountKind.EQUITY, cny, ownedByUser = false, realAccount = false),
            ),
            categories = emptyList(),
        ),
    )
}
