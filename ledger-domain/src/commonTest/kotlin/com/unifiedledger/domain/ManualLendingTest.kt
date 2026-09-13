package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-02.C L-2..L-5 product manual-lending domain contract (B02/B03 core): two-leg LEND and
 * three-leg COLLECT, per-object monotonic time, principal never ordinary income/expense, fee
 * fixed to zero, and the frozen [createLendingPosition]/[createLendingSettlement] reused as the
 * only rebuild/validation surface.
 */
class ManualLendingTest {
    private val ledgerId = LedgerId("ledger-c")
    private val cny = CurrencyUnit("CNY", 2)
    private val cp1 = CounterpartyId("cp-1")
    private val cp2 = CounterpartyId("cp-2")
    private val fundingAccountId = AccountId("asset-bank")
    private val destinationAccountId = AccountId("asset-wallet")
    private val receivable1 = AccountId("recv-cp-1")
    private val receivable2 = AccountId("recv-cp-2")
    private val incomeAccountId = AccountId("income-lending-interest")
    private val interestGroupId = CategoryId("income-group")
    private val interestCategoryId = CategoryId("income-lending-interest-cat")

    private val catalog =
        success(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(fundingAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "bank"),
                        Account(destinationAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "wallet"),
                        Account(receivable1, ledgerId, AccountKind.ASSET, cny, ownedByUser = false, realAccount = false, name = "cp1 receivable"),
                        Account(receivable2, ledgerId, AccountKind.ASSET, cny, ownedByUser = false, realAccount = false, name = "cp2 receivable"),
                        Account(incomeAccountId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false, name = "interest"),
                    ),
                categories =
                    listOf(
                        Category(interestGroupId, ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                        Category(interestCategoryId, ledgerId, parentId = interestGroupId, postingAccountId = incomeAccountId, active = true, kind = CategoryKind.INCOME),
                    ),
            ),
        )

    private fun emptyPosition(
        counterparty: CounterpartyId,
        receivable: AccountId,
    ): LendingPosition =
        success(
            createLendingPosition(
                id = "position-${counterparty.value}",
                counterpartyId = counterparty.value,
                receivableAccountId = receivable,
                currency = cny,
                principalBalanceMinor = 0L,
                history = emptyList(),
            ),
        )

    private fun lendIds(
        suffix: String,
        transactionId: String,
    ): ManualLendIds =
        ManualLendIds(
            entryId = "entry-lend-$suffix",
            transactionId = TransactionId(transactionId),
            versionId = TransactionVersionId("version-$transactionId"),
            postingSetId = PostingSetId("postingset-$transactionId"),
            receivablePostingId = PostingId("posting-recv-$suffix"),
            fundingPostingId = PostingId("posting-fund-$suffix"),
        )

    private fun collectIds(
        suffix: String,
        transactionId: String,
    ): ManualCollectIds =
        ManualCollectIds(
            entryId = "entry-collect-$suffix",
            transactionId = TransactionId(transactionId),
            versionId = TransactionVersionId("version-$transactionId"),
            postingSetId = PostingSetId("postingset-$transactionId"),
            destinationPostingId = PostingId("posting-dest-$suffix"),
            principalPostingId = PostingId("posting-principal-$suffix"),
            interestPostingId = PostingId("posting-interest-$suffix"),
        )

    @Test
    fun lendAppendsReceivableHistoryAndKeepsNetWorthFlat() {
        val first =
            success(
                createManualLend(
                    catalog,
                    emptyPosition(cp1, receivable1),
                    ManualLendCommand(
                        ledgerId = ledgerId,
                        counterpartyId = cp1,
                        receivableAccountId = receivable1,
                        fundingAccountId = fundingAccountId,
                        amount = money(10_000, cny),
                        times = TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z")),
                    ),
                    lendIds("1", "tx-lend-1"),
                ),
            )
        assertEquals(10_000L, first.position.principalBalanceMinor)
        assertEquals(TransactionKind.LEND, first.formalTransaction.transaction.kind)
        assertEquals(2, first.formalTransaction.currentPostings().size)
        assertEquals(0L, first.reportEffects.netWorthChangeMinor)
        assertEquals(0L, first.reportEffects.ordinaryIncomeMinor)
        assertEquals(0L, first.reportEffects.ordinaryExpenseMinor)
        assertEquals(-10_000L, first.reportEffects.principalCashFlowMinor)

        val second =
            success(
                createManualLend(
                    catalog,
                    first.position,
                    ManualLendCommand(
                        ledgerId = ledgerId,
                        counterpartyId = cp1,
                        receivableAccountId = receivable1,
                        fundingAccountId = fundingAccountId,
                        amount = money(2_000, cny),
                        times = TransactionTimes.collapsed(Instant.parse("2026-03-02T00:00:00Z")),
                    ),
                    lendIds("2", "tx-lend-2"),
                ),
            )
        assertEquals(12_000L, second.position.principalBalanceMinor)
        assertEquals(listOf(10_000L, 12_000L), second.position.history.map { it.principalBalanceAfterMinor })
    }

    @Test
    fun collectSplitsPrincipalFromInterestAndLeavesRemainingBalance() {
        val lend =
            success(
                createManualLend(
                    catalog,
                    emptyPosition(cp1, receivable1),
                    ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(12_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))),
                    lendIds("1", "tx-lend-1"),
                ),
            )
        val collect =
            success(
                createManualCollect(
                    catalog,
                    lend.position,
                    ManualCollectCommand(
                        ledgerId = ledgerId,
                        counterpartyId = cp1,
                        receivableAccountId = receivable1,
                        destinationAccountId = destinationAccountId,
                        interestCategoryId = interestCategoryId,
                        totalReceived = money(4_500, cny),
                        principal = money(4_000, cny),
                        interest = money(500, cny),
                        fee = money(0, cny),
                        times = TransactionTimes.collapsed(Instant.parse("2026-03-03T00:00:00Z")),
                    ),
                    collectIds("1", "tx-collect-1"),
                ),
            )
        assertEquals(8_000L, collect.position.principalBalanceMinor)
        assertEquals(TransactionKind.COLLECT, collect.formalTransaction.transaction.kind)
        assertEquals(3, collect.formalTransaction.currentPostings().size)
        assertEquals(4_500L, collect.reportEffects.cashInflowMinor)
        assertEquals(500L, collect.reportEffects.ordinaryIncomeMinor)
        assertEquals(0L, collect.reportEffects.ordinaryExpenseMinor)
        assertEquals(0L, collect.reportEffects.consumptionMinor)
        assertEquals(500L, collect.reportEffects.netWorthChangeMinor)
        assertEquals(4_000L, collect.reportEffects.principalCashFlowMinor)
        assertEquals(0L, collect.reportEffects.feeMinor)
    }

    @Test
    fun objectsAreIsolated() {
        val a =
            success(
                createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("1", "tx-lend-1")),
            )
        val b =
            success(
                createManualLend(catalog, emptyPosition(cp2, receivable2), ManualLendCommand(ledgerId, cp2, receivable2, fundingAccountId, money(3_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("2", "tx-lend-2")),
            )
        assertEquals(10_000L, a.position.principalBalanceMinor)
        assertEquals(3_000L, b.position.principalBalanceMinor)
    }

    @Test
    fun collectRejectsNonZeroFee() {
        val lend = success(createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("1", "tx-lend-1")))
        val failure =
            failure(
                createManualCollect(
                    catalog,
                    lend.position,
                    ManualCollectCommand(ledgerId, cp1, receivable1, destinationAccountId, interestCategoryId, money(4_100, cny), money(4_000, cny), money(0, cny), money(100, cny), TransactionTimes.collapsed(Instant.parse("2026-03-02T00:00:00Z"))),
                    collectIds("1", "tx-collect-1"),
                ),
            )
        assertIs<ManualLendingViolation.LendingFeeMustBeZero>(failure)
    }

    @Test
    fun collectRejectsPrincipalExceedingBalance() {
        val lend = success(createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("1", "tx-lend-1")))
        val failure =
            failure(
                createManualCollect(
                    catalog,
                    lend.position,
                    ManualCollectCommand(ledgerId, cp1, receivable1, destinationAccountId, interestCategoryId, money(10_001, cny), money(10_001, cny), money(0, cny), money(0, cny), TransactionTimes.collapsed(Instant.parse("2026-03-02T00:00:00Z"))),
                    collectIds("1", "tx-collect-1"),
                ),
            )
        assertIs<ManualLendingViolation.LendingPrincipalExceedsBalance>(failure)
    }

    @Test
    fun collectRejectsNegativeComposition() {
        val lend = success(createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("1", "tx-lend-1")))
        val failure =
            failure(
                createManualCollect(
                    catalog,
                    lend.position,
                    ManualCollectCommand(ledgerId, cp1, receivable1, destinationAccountId, interestCategoryId, money(4_000, cny), money(4_500, cny), money(-500, cny), money(0, cny), TransactionTimes.collapsed(Instant.parse("2026-03-02T00:00:00Z"))),
                    collectIds("1", "tx-collect-1"),
                ),
            )
        assertIs<ManualLendingViolation.LendingComponentsMismatch>(failure)
    }

    @Test
    fun collectRequiresActiveLeafInterestCategory() {
        val lend = success(createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("1", "tx-lend-1")))
        val failure =
            failure(
                createManualCollect(
                    catalog,
                    lend.position,
                    ManualCollectCommand(ledgerId, cp1, receivable1, destinationAccountId, CategoryId("missing"), money(4_000, cny), money(4_000, cny), money(0, cny), money(0, cny), TransactionTimes.collapsed(Instant.parse("2026-03-02T00:00:00Z"))),
                    collectIds("1", "tx-collect-1"),
                ),
            )
        assertIs<ManualLendingViolation.LendingInterestCategoryRequired>(failure)
    }

    @Test
    fun collectRejectsIneligibleDestinationAccount() {
        val lend = success(createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("1", "tx-lend-1")))
        val failure =
            failure(
                createManualCollect(
                    catalog,
                    lend.position,
                    ManualCollectCommand(ledgerId, cp1, receivable1, receivable1, interestCategoryId, money(4_000, cny), money(4_000, cny), money(0, cny), money(0, cny), TransactionTimes.collapsed(Instant.parse("2026-03-02T00:00:00Z"))),
                    collectIds("1", "tx-collect-1"),
                ),
            )
        assertIs<ManualLendingViolation.LendingAccountNotEligible>(failure)
    }

    @Test
    fun backdatedEventIsRejectedAndZeroWrites() {
        val lend = success(createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-05T00:00:00Z"))), lendIds("1", "tx-lend-1")))
        val failure =
            failure(
                createManualLend(catalog, lend.position, ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(2_000, cny), TransactionTimes.collapsed(Instant.parse("2026-03-04T00:00:00Z"))), lendIds("2", "tx-lend-2")),
            )
        assertIs<ManualLendingViolation.LendingBackdatedNotAllowed>(failure)
    }

    @Test
    fun sameInstantAppendIsAllowed() {
        val at = Instant.parse("2026-03-05T00:00:00Z")
        val first = success(createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(10_000, cny), TransactionTimes.collapsed(at)), lendIds("1", "tx-lend-1")))
        val second = success(createManualLend(catalog, first.position, ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(2_000, cny), TransactionTimes.collapsed(at)), lendIds("2", "tx-lend-2")))
        assertEquals(12_000L, second.position.principalBalanceMinor)
    }

    @Test
    fun lendRejectsNonPositiveAmount() {
        val failure =
            failure(
                createManualLend(catalog, emptyPosition(cp1, receivable1), ManualLendCommand(ledgerId, cp1, receivable1, fundingAccountId, money(0, cny), TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z"))), lendIds("1", "tx-lend-1")),
            )
        assertIs<ManualLendingViolation.LendingAmountMustBePositive>(failure)
    }
}
