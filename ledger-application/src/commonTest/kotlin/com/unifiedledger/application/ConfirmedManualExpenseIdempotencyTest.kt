package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionNoteUpdateCommand
import com.unifiedledger.domain.TransactionNoteUpdateIds
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.replaceNote
import com.unifiedledger.domain.replayBalances
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class ConfirmedManualExpenseIdempotencyTest {
    @Test
    fun firstExplicitSaveAtomicallyCommitsOneExpenseAndReturnsAStableReceipt() {
        val fixture = Rg01ApplicationFixture()
        val harness = ConfirmedExpenseHarness(fixture)
        val request = fixture.request()

        val result = harness.execute(request)

        val created = assertIs<ConfirmedManualExpenseResult.Created>(result)
        assertEquals(ConfirmationId("confirmation-rg01-1"), created.receipt.confirmationId)
        assertEquals(TransactionId("tx-expense-rg01"), created.receipt.transactionId)
        assertEquals(1, harness.idSource.invocationCount)
        assertEquals(1, harness.transactionCreateCount)
        assertEquals(1, harness.commitPort.commitCount)
        assertEquals(1, harness.commitPort.transactions.size)
        assertEquals(
            fixture.money(96_420),
            harness.balances().getValue(fixture.paymentAccountId),
        )
    }

    @Test
    fun replayAfterNoteReplacementReturnsOnlyTheOriginalReceiptAndDoesNotRecreateOrRewindState() {
        val fixture = Rg01ApplicationFixture()
        val harness = ConfirmedExpenseHarness(fixture)
        val request = fixture.request()
        val created = assertIs<ConfirmedManualExpenseResult.Created>(harness.execute(request))
        val replacementVersionId = TransactionVersionId("version-expense-rg01-v2")
        harness.commitPort.replaceCommittedTransaction(
            index = 0,
            replacement =
                success(
                    harness.commitPort.transactions.single().replaceNote(
                        command = TransactionNoteUpdateCommand(note = "早餐"),
                        ids = TransactionNoteUpdateIds(versionId = replacementVersionId),
                    ),
                ),
        )
        val stateBeforeReplay = harness.commitPort.transactions.single()

        val replayed =
            assertIs<ConfirmedManualExpenseResult.NoChange>(
                harness.execute(request),
            )

        assertEquals(created.receipt, replayed.receipt)
        assertEquals(1, harness.idSource.invocationCount)
        assertEquals(1, harness.transactionCreateCount)
        assertEquals(1, harness.commitPort.commitCount)
        assertEquals(1, harness.commitPort.transactions.size)
        assertEquals(stateBeforeReplay, harness.commitPort.transactions.single())
        assertEquals(
            replacementVersionId,
            harness.commitPort.transactions
                .single()
                .transaction.currentVersionId,
        )
        assertEquals(
            "早餐",
            harness.commitPort.transactions
                .single()
                .versions
                .single { it.id == replacementVersionId }
                .note,
        )
        assertEquals(
            fixture.money(96_420),
            harness.balances().getValue(fixture.paymentAccountId),
        )
    }

    @Test
    fun aNewRequestIdentityCommitsASecondExpenseWhenEveryEconomicFieldIsIdentical() {
        val fixture = Rg01ApplicationFixture()
        val harness = ConfirmedExpenseHarness(fixture)
        val firstRequest = fixture.request()
        val secondRequest =
            firstRequest.copy(
                requestId = RequestId("request-rg01-distinct-create"),
            )

        val first = assertIs<ConfirmedManualExpenseResult.Created>(harness.execute(firstRequest))
        val second = assertIs<ConfirmedManualExpenseResult.Created>(harness.execute(secondRequest))

        assertEquals(firstRequest.amount, secondRequest.amount)
        assertEquals(firstRequest.categoryId, secondRequest.categoryId)
        assertEquals(firstRequest.paymentAccountId, secondRequest.paymentAccountId)
        assertEquals(firstRequest.occurredAt, secondRequest.occurredAt)
        assertEquals(firstRequest.note, secondRequest.note)
        assertEquals(2, harness.transactionCreateCount)
        assertEquals(2, harness.commitPort.commitCount)
        assertEquals(2, harness.commitPort.transactions.size)
        assertNotEquals(first.receipt.confirmationId, second.receipt.confirmationId)
        assertNotEquals(first.receipt.transactionId, second.receipt.transactionId)
        assertEquals(2, harness.idSource.invocationCount)

        val firstTransaction = harness.commitPort.transactions[0]
        val secondTransaction = harness.commitPort.transactions[1]
        assertNotEquals(firstTransaction.transaction.id, secondTransaction.transaction.id)
        assertEquals(
            emptySet(),
            firstTransaction.postingSets
                .map { it.id }
                .toSet()
                .intersect(secondTransaction.postingSets.map { it.id }.toSet()),
        )
        assertEquals(
            emptySet(),
            firstTransaction.postingSets
                .flatMap { it.postings }
                .map { it.id }
                .toSet()
                .intersect(
                    secondTransaction.postingSets
                        .flatMap { it.postings }
                        .map { it.id }
                        .toSet(),
                ),
        )
        assertEquals(
            fixture.money(92_840),
            harness.balances().getValue(fixture.paymentAccountId),
        )
    }

    @Test
    fun theSameRequestIdentityWithChangedInputReturnsTypedConflictAndPerformsNoWrite() {
        val fixture = Rg01ApplicationFixture()
        val harness = ConfirmedExpenseHarness(fixture)
        val original = fixture.request()
        harness.execute(original)
        val stateBeforeConflicts = harness.commitPort.transactions.toList()
        val identity =
            ManualExpenseRequestIdentity(
                ledgerId = original.ledgerId,
                requestId = original.requestId,
            )
        val conflictingRequests =
            listOf(
                original.copy(amount = fixture.money(3_581)),
                original.copy(amount = Money.ofMinor(3_580, CurrencyUnit("USD", 2))),
                original.copy(categoryId = CategoryId("expense-category-other")),
                original.copy(paymentAccountId = AccountId("asset-bank-other")),
                original.copy(occurredAt = Instant.parse("2026-01-15T00:31:00Z")),
                original.copy(note = "changed note"),
            )

        conflictingRequests.forEach { conflict ->
            val result =
                assertIs<ConfirmedManualExpenseResult.RequestIdentityConflict>(
                    harness.execute(conflict),
                )
            assertEquals(identity, result.identity)
        }

        assertEquals(1, harness.transactionCreateCount)
        assertEquals(1, harness.idSource.invocationCount)
        assertEquals(1, harness.commitPort.commitCount)
        assertEquals(stateBeforeConflicts, harness.commitPort.transactions)
        assertEquals(
            fixture.money(96_420),
            harness.balances().getValue(fixture.paymentAccountId),
        )
    }

    @Test
    fun theFormalEntryPointRequiresTheAffirmativeExplicitManualSaveMarker() {
        val fixture = Rg01ApplicationFixture()

        val request = fixture.request(confirmation = ExplicitManualSave)
        val confirmation: ExplicitManualSave = request.confirmation

        assertEquals(ExplicitManualSave, confirmation)
    }

    @Test
    fun aRejectedDomainTransactionWritesNothingAndDoesNotOccupyTheRequestIdentity() {
        val fixture = Rg01ApplicationFixture()
        val harness = ConfirmedExpenseHarness(fixture)
        val invalid = fixture.request().copy(amount = fixture.money(0))

        val rejected =
            assertIs<ConfirmedManualExpenseResult.Rejected>(
                harness.execute(invalid),
            )

        assertEquals(OrdinaryExpenseViolation.AmountMustBePositive, rejected.violation)
        assertEquals(1, harness.idSource.invocationCount)
        assertEquals(1, harness.transactionCreateCount)
        assertEquals(0, harness.commitPort.commitCount)
        assertEquals(emptyList(), harness.commitPort.transactions)

        val corrected = invalid.copy(amount = fixture.money(3_580))
        val created =
            assertIs<ConfirmedManualExpenseResult.Created>(
                harness.execute(corrected),
            )

        assertEquals(ConfirmationId("confirmation-rg01-2"), created.receipt.confirmationId)
        assertEquals(TransactionId("tx-expense-rg01-distinct"), created.receipt.transactionId)
        assertEquals(2, harness.idSource.invocationCount)
        assertEquals(2, harness.transactionCreateCount)
        assertEquals(1, harness.commitPort.commitCount)
        assertEquals(1, harness.commitPort.transactions.size)
        assertEquals(
            fixture.money(96_420),
            harness.balances().getValue(fixture.paymentAccountId),
        )
    }
}

private class ConfirmedExpenseHarness(
    private val fixture: Rg01ApplicationFixture,
) {
    val commitPort = InMemoryConfirmedManualExpenseCommitPort()
    val idSource = SequentialConfirmedManualExpenseIdSource(fixture)
    var transactionCreateCount = 0
        private set

    private val useCase =
        ExecuteConfirmedManualExpense(
            commitPort = commitPort,
            idSource = idSource,
            createFormalTransaction =
                ConfirmedExpenseTransactionFactory { request, ids ->
                    transactionCreateCount += 1
                    check(request.note.isEmpty()) {
                        "This RG-01 slice creates only the frozen empty initial note"
                    }
                    when (
                        val result =
                            createAssetPaidOrdinaryExpense(
                                catalog = fixture.catalog,
                                command =
                                    AssetPaidOrdinaryExpenseCommand(
                                        ledgerId = request.ledgerId,
                                        amount = request.amount,
                                        categoryId = request.categoryId,
                                        paymentAccountId = request.paymentAccountId,
                                        times = TransactionTimes.collapsed(request.occurredAt),
                                    ),
                                ids = ids.expenseIds,
                            )
                    ) {
                        is DomainResult.Success ->
                            DomainResult.Success(
                                ConfirmedManualExpenseCommit(
                                    confirmationId = ids.confirmationId,
                                    transaction = result.value,
                                ),
                            )
                        is DomainResult.Failure -> result
                    }
                },
        )

    fun execute(request: ExplicitlyConfirmedManualExpense): ConfirmedManualExpenseResult = useCase.execute(request)

    fun balances() =
        success(
            replayBalances(
                catalog = fixture.catalog,
                transactions = listOf(fixture.openingBalance) + commitPort.transactions,
            ),
        ).balances
}

private class InMemoryConfirmedManualExpenseCommitPort : ConfirmedManualExpenseCommitPort {
    private data class CommittedRequest(
        val snapshot: ManualExpenseRequestSnapshot,
        val receipt: ConfirmedExpenseReceipt,
    )

    private val requests = mutableMapOf<ManualExpenseRequestIdentity, CommittedRequest>()
    private val committedTransactions = mutableListOf<FormalTransaction>()
    val transactions: List<FormalTransaction>
        get() = committedTransactions.toList()
    var commitCount = 0
        private set

    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult {
        val existing = requests[identity]
        if (existing != null) {
            return if (existing.snapshot == requestSnapshot) {
                ConfirmedManualExpenseResult.NoChange(existing.receipt)
            } else {
                ConfirmedManualExpenseResult.RequestIdentityConflict(identity)
            }
        }

        val commit =
            when (val creationResult = createFormalTransaction()) {
                is DomainResult.Success -> creationResult.value
                is DomainResult.Failure -> {
                    return ConfirmedManualExpenseResult.Rejected(creationResult.violation)
                }
            }
        val receipt =
            ConfirmedExpenseReceipt(
                confirmationId = commit.confirmationId,
                transactionId = commit.transaction.transaction.id,
            )
        requests[identity] = CommittedRequest(requestSnapshot, receipt)
        committedTransactions += commit.transaction
        commitCount += 1
        return ConfirmedManualExpenseResult.Created(receipt)
    }

    fun replaceCommittedTransaction(
        index: Int,
        replacement: FormalTransaction,
    ) {
        committedTransactions[index] = replacement
    }
}

private class SequentialConfirmedManualExpenseIdSource(
    private val fixture: Rg01ApplicationFixture,
) : ConfirmedManualExpenseIdSource {
    var invocationCount = 0
        private set

    override fun next(): ConfirmedManualExpenseCommitIds {
        val ordinal = invocationCount++
        return ConfirmedManualExpenseCommitIds(
            confirmationId = ConfirmationId("confirmation-rg01-${ordinal + 1}"),
            expenseIds = fixture.expenseIds(ordinal),
        )
    }
}

private class Rg01ApplicationFixture {
    val ledgerId = LedgerId("ledger-a")
    val cny = CurrencyUnit("CNY", 2)
    val paymentAccountId = AccountId("asset-bank-a")
    val expenseAccountId = AccountId("expense-account-breakfast")
    private val equityAccountId = AccountId("equity-opening-a")
    private val parentCategoryId = CategoryId("expense-category-food")
    private val categoryId = CategoryId("expense-category-breakfast")
    private val expenseOccurredAt = Instant.parse("2026-01-15T00:30:00Z")

    val catalog =
        success(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(
                            id = paymentAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.ASSET,
                            currency = cny,
                            ownedByUser = true,
                            realAccount = true,
                        ),
                        Account(
                            id = expenseAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.EXPENSE,
                            currency = cny,
                            ownedByUser = false,
                            realAccount = false,
                        ),
                        Account(
                            id = equityAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.EQUITY,
                            currency = cny,
                            ownedByUser = false,
                            realAccount = false,
                        ),
                    ),
                categories =
                    listOf(
                        Category(
                            id = parentCategoryId,
                            ledgerId = ledgerId,
                            parentId = null,
                            postingAccountId = null,
                            active = true,
                        ),
                        Category(
                            id = categoryId,
                            ledgerId = ledgerId,
                            parentId = parentCategoryId,
                            postingAccountId = expenseAccountId,
                            active = true,
                        ),
                    ),
            ),
        )

    val openingBalance: FormalTransaction =
        run {
            val transactionId = TransactionId("tx-opening-a")
            val versionId = TransactionVersionId("version-opening-a-v1")
            val postingSetId = PostingSetId("posting-set-opening-a")
            val postingSet =
                success(
                    PostingSet.create(
                        id = postingSetId,
                        postings =
                            listOf(
                                Posting(
                                    id = PostingId("posting-opening-bank-a"),
                                    accountId = paymentAccountId,
                                    amount = money(100_000),
                                ),
                                Posting(
                                    id = PostingId("posting-opening-equity-a"),
                                    accountId = equityAccountId,
                                    amount = money(-100_000),
                                ),
                            ),
                    ),
                )
            success(
                FormalTransaction.create(
                    transaction =
                        Transaction(
                            id = transactionId,
                            ledgerId = ledgerId,
                            kind = TransactionKind.OPENING_BALANCE,
                            currentVersionId = versionId,
                        ),
                    versions =
                        listOf(
                            TransactionVersion(
                                id = versionId,
                                transactionId = transactionId,
                                versionNumber = 1,
                                postingSetId = postingSetId,
                                times =
                                    TransactionTimes.collapsed(
                                        Instant.parse("2025-12-31T16:00:00Z"),
                                    ),
                            ),
                        ),
                    postingSets = listOf(postingSet),
                ),
            )
        }

    fun request(
        confirmation: ExplicitManualSave = ExplicitManualSave,
    ) = ExplicitlyConfirmedManualExpense(
        ledgerId = ledgerId,
        requestId = RequestId("request-rg01-create"),
        amount = money(3_580),
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        occurredAt = expenseOccurredAt,
        note = "",
        confirmation = confirmation,
    )

    fun money(minorUnits: Long): Money = Money.ofMinor(minorUnits, cny)

    fun expenseIds(ordinal: Int): AssetPaidOrdinaryExpenseIds {
        val suffix = if (ordinal == 0) "rg01" else "rg01-distinct"
        return AssetPaidOrdinaryExpenseIds(
            transactionId = TransactionId("tx-expense-$suffix"),
            versionId = TransactionVersionId("version-expense-$suffix-v1"),
            postingSetId = PostingSetId("posting-set-expense-$suffix"),
            expensePostingId = PostingId("posting-expense-$suffix"),
            paymentPostingId = PostingId("posting-bank-$suffix"),
        )
    }
}

private inline fun <reified T> success(result: DomainResult<T>): T = assertIs<DomainResult.Success<T>>(result).value
