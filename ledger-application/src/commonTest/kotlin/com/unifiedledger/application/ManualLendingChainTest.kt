package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.Counterparty
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CounterpartyNameVersion
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.LendingPositionHistoryEntry
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createLendingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-02.C application lending chain: the product transaction factory reuses the domain
 * LEND/COLLECT constructors behind the V-2 admission + counterparty directory read, the save
 * gates report missing fields, and a post-handoff failure recovers/unknowns through the
 * snapshot-aware resolver.
 */
class ManualLendingChainTest {
    private val ledgerId = LedgerId("ledger-c")
    private val cny = CurrencyUnit("CNY", 2)
    private val counterpartyId = CounterpartyId("cp-alice")
    private val receivableId = AccountId("recv-alice")
    private val fundingId = AccountId("asset-bank")
    private val destinationId = AccountId("asset-wallet")
    private val interestAccountId = AccountId("income-interest")
    private val interestGroupId = CategoryId("income-group")
    private val interestCategoryId = CategoryId("income-interest-cat")
    private val at = Instant.parse("2026-03-01T00:00:00Z")

    private val catalog =
        success(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(fundingId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                        Account(destinationId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                        Account(receivableId, ledgerId, AccountKind.ASSET, cny, ownedByUser = false, realAccount = false),
                        Account(interestAccountId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                    ),
                categories =
                    listOf(
                        Category(interestGroupId, ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                        Category(interestCategoryId, ledgerId, parentId = interestGroupId, postingAccountId = interestAccountId, active = true, kind = CategoryKind.INCOME),
                    ),
            ),
        )

    private fun counterparty(active: Boolean = true): Counterparty =
        Counterparty(
            id = counterpartyId,
            ledgerId = ledgerId,
            name = "Alice",
            receivableAccountId = receivableId,
            active = active,
            nameHistory = listOf(CounterpartyNameVersion(1, "Alice", true)),
        )

    private fun position(balanceMinor: Long): LendingPosition =
        success(
            createLendingPosition(
                id = "position-cp-alice",
                counterpartyId = counterpartyId.value,
                receivableAccountId = receivableId,
                currency = cny,
                principalBalanceMinor = balanceMinor,
                history =
                    if (balanceMinor == 0L) {
                        emptyList()
                    } else {
                        listOf(LendingPositionHistoryEntry("entry-seed", com.unifiedledger.domain.LendingBehaviorCode.LEND, balanceMinor, balanceMinor, TransactionId("tx-seed"), Instant.parse("2026-02-01T00:00:00Z")))
                    },
            ),
        )

    private fun factory(
        counterparty: Counterparty = counterparty(),
        position: LendingPosition = position(0L),
        catalogOverride: LedgerCatalog = catalog,
    ): ManualLendingTransactionFactory =
        ManualLendingTransactionFactory(
            admissionReader = { catalogOverride },
            counterpartyReader =
                object : CounterpartyDirectoryReader {
                    override fun find(
                        ledgerId: LedgerId,
                        counterpartyId: CounterpartyId,
                    ): Counterparty? = counterparty

                    override fun list(ledgerId: LedgerId): List<Counterparty> = listOf(counterparty)
                },
            positionReader = { _, _, _, _ -> position },
        )

    private fun lendIds(): ConfirmedManualLendingCommitIds =
        ConfirmedManualLendingCommitIds(
            confirmationId = ConfirmationId("conf-1"),
            entryId = "entry-1",
            lendingIds =
                ManualLendingTransactionIds(
                    transactionId = TransactionId("tx-1"),
                    versionId = TransactionVersionId("v1"),
                    postingSetId = PostingSetId("ps1"),
                    counterpartyPostingId = PostingId("p-recv"),
                    primaryAccountPostingId = PostingId("p-fund"),
                    interestPostingId = PostingId("p-interest"),
                ),
        )

    private fun lendSnapshot(
        amountMinor: Long = 10_000L,
        occurredAt: Instant = at,
    ): ManualLendingRequestSnapshot =
        ManualLendingRequestSnapshot(
            ledgerId = ledgerId,
            behavior = ManualLendingBehavior.LEND,
            counterpartyId = counterpartyId,
            principalAccountId = fundingId,
            amount = Money.ofMinor(amountMinor, cny),
            interest = Money.ofMinor(0L, cny),
            fee = Money.ofMinor(0L, cny),
            totalReceived = null,
            interestCategoryId = null,
            occurredAt = occurredAt,
            note = "",
        )

    private fun collectSnapshot(
        totalReceived: Long = 4_500L,
        principal: Long = 4_000L,
        interest: Long = 500L,
        occurredAt: Instant = at,
    ): ManualLendingRequestSnapshot =
        ManualLendingRequestSnapshot(
            ledgerId = ledgerId,
            behavior = ManualLendingBehavior.COLLECT,
            counterpartyId = counterpartyId,
            principalAccountId = destinationId,
            amount = Money.ofMinor(principal, cny),
            interest = Money.ofMinor(interest, cny),
            fee = Money.ofMinor(0L, cny),
            totalReceived = Money.ofMinor(totalReceived, cny),
            interestCategoryId = interestCategoryId,
            occurredAt = occurredAt,
            note = "",
        )

    @Test
    fun factoryBuildsLendAndCollectThroughTheDomainConstructors() {
        val lend = success(factory().create(lendSnapshot(), lendIds()))
        assertEquals(com.unifiedledger.domain.TransactionKind.LEND, lend.transaction.transaction.kind)
        assertEquals(2, lend.transaction.currentPostings().size)
        assertEquals(10_000L, lend.position.principalBalanceMinor)

        val collect = success(factory(position = position(12_000L)).create(collectSnapshot(), lendIds()))
        assertEquals(com.unifiedledger.domain.TransactionKind.COLLECT, collect.transaction.transaction.kind)
        assertEquals(3, collect.transaction.currentPostings().size)
        assertEquals(8_000L, collect.position.principalBalanceMinor)
    }

    @Test
    fun inactiveOrMissingCounterpartyIsTyped() {
        val missing =
            failure(
                ManualLendingTransactionFactory(
                    admissionReader = { catalog },
                    counterpartyReader =
                        object : CounterpartyDirectoryReader {
                            override fun find(
                                ledgerId: LedgerId,
                                counterpartyId: CounterpartyId,
                            ): Counterparty? = null

                            override fun list(ledgerId: LedgerId): List<Counterparty> = emptyList()
                        },
                    positionReader = { _, _, _, _ -> position(0L) },
                ).create(lendSnapshot(), lendIds()),
            )
        assertIs<com.unifiedledger.domain.ManualLendingViolation.LendingCounterpartyNotFound>(missing)

        val inactive = failure(factory(counterparty = counterparty(active = false)).create(lendSnapshot(), lendIds()))
        assertIs<com.unifiedledger.domain.ManualLendingViolation.LendingCounterpartyNotFound>(inactive)
    }

    @Test
    fun ineligibleFundingAccountAndInterestCategoryAreTyped() {
        val badAccount = failure(factory().create(lendSnapshot().copy(principalAccountId = receivableId), lendIds()))
        assertIs<com.unifiedledger.domain.CatalogAdmissionRejection.PaymentAccountNotManageableFinancial>(badAccount)

        val badCategory = failure(factory(position = position(12_000L)).create(collectSnapshot().copy(interestCategoryId = CategoryId("missing")), lendIds()))
        assertIs<com.unifiedledger.domain.CatalogAdmissionRejection.CategoryNotFound>(badCategory)
    }

    @Test
    fun overCollectionAndBackdatingAreTyped() {
        val over = failure(factory(position = position(1_000L)).create(collectSnapshot(totalReceived = 2_000L, principal = 2_000L, interest = 0L), lendIds()))
        assertIs<com.unifiedledger.domain.ManualLendingViolation.LendingPrincipalExceedsBalance>(over)

        val backdated = failure(factory(position = position(10_000L)).create(lendSnapshot(amountMinor = 1_000L, occurredAt = Instant.parse("2026-01-15T00:00:00Z")), lendIds()))
        assertIs<com.unifiedledger.domain.ManualLendingViolation.LendingBackdatedNotAllowed>(backdated)
    }

    @Test
    fun saveGatesReportMissingFields() {
        val execute =
            ExecuteManualLendingSave(
                ExecuteConfirmedManualLending(
                    commitPort = { _, _, _ -> error("no commit") },
                    idSource = { error("no ids") },
                    createFormalTransaction = { _, _ -> error("no factory") },
                ),
            )
        val lend =
            assertIs<ManualLendSaveResult.InvalidInput>(
                execute.saveLend(ManualLendSaveInput(ledgerId, RequestId("r"), null, null, null, at, "", ExplicitManualSave)),
            )
        assertEquals(setOf(ManualLendInputField.COUNTERPARTY, ManualLendInputField.FUNDING_ACCOUNT, ManualLendInputField.AMOUNT), lend.fields)
        val collect =
            assertIs<ManualCollectSaveResult.InvalidInput>(
                execute.saveCollect(ManualCollectSaveInput(ledgerId, RequestId("r"), null, null, null, null, null, null, at, "", ExplicitManualSave)),
            )
        assertEquals(
            setOf(ManualCollectInputField.COUNTERPARTY, ManualCollectInputField.DESTINATION_ACCOUNT, ManualCollectInputField.TOTAL_RECEIVED, ManualCollectInputField.PRINCIPAL, ManualCollectInputField.INTEREST, ManualCollectInputField.INTEREST_CATEGORY),
            collect.fields,
        )
    }

    @Test
    fun postHandoffFailureWithoutRecordStaysUnknown() {
        val tracker = CommitOnceInvocationTrackerLending { _, _, _ -> error("handoff before persistence") }
        val submission =
            ExecuteManualLendingSubmission(
                ExecuteManualLendingSave(ExecuteConfirmedManualLending(tracker, { error("no ids") }, { _, _ -> error("no factory") })),
                tracker,
                ResolveManualLendingCommitStatus(emptyPort(null)),
            )
        val input =
            ManualLendSaveInput(ledgerId, RequestId("r"), counterpartyId, fundingId, Money.ofMinor(1_000L, cny), at, "", ExplicitManualSave)
        assertEquals(ManualLendSubmissionResult.UnknownCommit, submission.saveLend(input))
    }

    private fun emptyPort(record: ManualLendingCommitRecord?): LedgerCurrentStateReadPort =
        object : LedgerCurrentStateReadPort {
            override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

            override fun findManualExpenseByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualExpenseCommitRecord? = null

            override fun findManualExpenseByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedExpenseReceipt,
            ): ManualExpenseCommitRecord? = null

            override fun findManualIncomeByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualIncomeCommitRecord? = null

            override fun findManualIncomeByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedIncomeReceipt,
            ): ManualIncomeCommitRecord? = null

            override fun findManualTransferByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualTransferCommitRecord? = null

            override fun findManualTransferByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedTransferReceipt,
            ): ManualTransferCommitRecord? = null

            override fun findManualLendingByRequest(
                ledgerId: LedgerId,
                requestId: RequestId,
            ): ManualLendingCommitRecord? = record

            override fun findManualLendingByReceipt(
                ledgerId: LedgerId,
                receipt: ConfirmedLendingReceipt,
            ): ManualLendingCommitRecord? = record
        }

    private fun <T> success(result: DomainResult<T>): T = assertIs<DomainResult.Success<T>>(result).value

    private fun <T> failure(result: DomainResult<T>): com.unifiedledger.domain.DomainViolation = assertIs<DomainResult.Failure>(result).violation
}
