package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StagedPaymentTest {
    private val fixture = StagedPaymentFixture()

    @Test
    fun createsIdentityOnlyRelationAndNonFinancialLifecycle() {
        val staged = fixture.created()

        assertEquals(fixture.relationId, staged.relation.id)
        assertEquals(
            setOf(StagedPaymentMemberRef.Lifecycle(fixture.lifecycleId)),
            staged.relation.memberRefs,
        )
        assertEquals(fixture.lifecycleId, staged.lifecycle.id)
        assertEquals(fixture.total, staged.lifecycle.totalAmount)
        assertEquals(fixture.zero, staged.lifecycle.paidAmount)
        assertEquals(fixture.total, staged.lifecycle.dueAmount)
        assertEquals(fixture.cny, staged.lifecycle.currency)
        assertEquals(fixture.categoryId, staged.lifecycle.categoryId)
        assertEquals("分阶段付款", staged.lifecycle.displayName)
        assertTrue(staged.lifecycle.systemManaged)
        assertEquals(false, staged.lifecycle.genericOrderLifecycle)
        assertEquals(emptyList(), staged.installments)
        assertEquals(emptyList(), staged.formalTransactions)

        val history = staged.lifecycle.stateHistory.single()
        assertEquals(1, history.sequence)
        assertEquals(StagedPaymentEvent.GROUP_CREATED, history.event)
        assertEquals(fixture.createdAt, history.occurredAt)
        assertEquals(fixture.total, history.totalAmount)
        assertEquals(fixture.zero, history.paidAmount)
        assertEquals(fixture.total, history.dueAmount)
        assertNull(history.paymentId)
        assertEquals(StagedPaymentProgress.UNPAID, history.paymentProgress)
        assertEquals(StagedPaymentFulfillment.IN_PROGRESS, history.fulfillmentStatus)
        assertEquals(0, history.stateTransitionEffectCount)
    }

    @Test
    fun depositDelegatesToOrdinaryExpenseAndAppendsItsIdentity() {
        val staged = fixture.deposited()
        val payment = staged.installments.single()

        assertEquals(fixture.depositPaymentId, payment.id)
        assertEquals(StagedPaymentRole.DEPOSIT, payment.role)
        assertEquals(fixture.depositAmount, payment.amount)
        assertEquals(fixture.fundingAccountId, payment.fundingAccountId)
        assertEquals(fixture.depositAt, payment.actualPaymentAt)
        assertEquals(fixture.depositAt, payment.statisticsAt)
        assertNull(payment.sourceTime)
        assertNull(payment.sourcePaymentAt)
        assertNull(payment.sourcePaymentAtText)
        assertEquals(
            setOf(
                StagedPaymentMemberRef.Lifecycle(fixture.lifecycleId),
                StagedPaymentMemberRef.Installment(fixture.depositPaymentId),
            ),
            staged.relation.memberRefs,
        )
        assertEquals(fixture.depositAmount, staged.lifecycle.paidAmount)
        assertEquals(fixture.finalAmount, staged.lifecycle.dueAmount)
        assertEquals(StagedPaymentProgress.PARTIALLY_PAID, staged.paymentProgress)

        val formal = staged.formalTransactions.single()
        assertEquals(TransactionKind.EXPENSE, formal.transaction.kind)
        assertEquals(fixture.depositIds.expenseIds.transactionId, formal.transaction.id)
        assertEquals(
            listOf(8_000L, -8_000L),
            formal.postingSets.single().postings.map { it.amount.minorUnits },
        )
        assertEquals(
            listOf(fixture.expenseAccountId, fixture.fundingAccountId),
            formal.postingSets.single().postings.map { it.accountId },
        )
        assertEquals(
            TransactionTimes.collapsed(fixture.depositAt),
            formal.versions.single().times,
        )
    }

    @Test
    fun finalCreatesASeparateBalancedExpenseForExactlyTheRemainingDue() {
        val staged = fixture.finalized()

        assertEquals(listOf(fixture.depositPaymentId, fixture.finalPaymentId), staged.installments.map { it.id })
        assertNotEquals(
            staged.formalTransactions[0].transaction.id,
            staged.formalTransactions[1].transaction.id,
        )
        assertEquals(
            listOf(
                listOf(8_000L, -8_000L),
                listOf(22_000L, -22_000L),
            ),
            staged.formalTransactions.map { formal ->
                formal.postingSets.single().postings.map { it.amount.minorUnits }
            },
        )
        assertEquals(fixture.total, staged.lifecycle.paidAmount)
        assertEquals(fixture.zero, staged.lifecycle.dueAmount)
        assertEquals(StagedPaymentProgress.PAID_IN_FULL, staged.paymentProgress)
        assertEquals(
            listOf(fixture.depositAt, fixture.finalAt),
            staged.installments.map { it.statisticsAt },
        )
        assertEquals(
            listOf(fixture.depositAt, fixture.finalAt),
            staged.formalTransactions.map { it.versions.single().times.statisticsAt },
        )
        assertEquals(
            setOf(
                StagedPaymentMemberRef.Lifecycle(fixture.lifecycleId),
                StagedPaymentMemberRef.Installment(fixture.depositPaymentId),
                StagedPaymentMemberRef.Installment(fixture.finalPaymentId),
            ),
            staged.relation.memberRefs,
        )
    }

    @Test
    fun rejectsDuplicateRolesAndAChangedPaymentIdentity() {
        val deposited = fixture.deposited()

        assertEquals(
            StagedPaymentViolation.DuplicateRole(StagedPaymentRole.DEPOSIT),
            failure(
                deposited.recordInstallment(
                    fixture.catalog,
                    fixture.depositCommand.copy(actualPaymentAt = fixture.finalAt),
                    fixture.paymentIds("duplicate-deposit", StagedPaymentRole.DEPOSIT),
                ),
            ),
        )

        val finalized = fixture.finalized()
        assertEquals(
            StagedPaymentViolation.DuplicateRole(StagedPaymentRole.FINAL),
            failure(
                finalized.recordInstallment(
                    fixture.catalog,
                    fixture.finalCommand.copy(actualPaymentAt = fixture.afterCompletionAt),
                    fixture.paymentIds("duplicate-final", StagedPaymentRole.FINAL),
                ),
            ),
        )

        assertEquals(
            StagedPaymentViolation.DuplicateIdentity,
            failure(
                deposited.recordInstallment(
                    fixture.catalog,
                    fixture.finalCommand,
                    fixture.finalIds.copy(paymentId = fixture.depositPaymentId),
                ),
            ),
        )
    }

    @Test
    fun rejectsCrossRolePostingIdentityCollisionsWithoutChangingTheAggregate() {
        val deposited = fixture.deposited()
        val existingExpensePostingId = fixture.depositIds.expenseIds.expensePostingId
        val existingAssetPostingId = fixture.depositIds.expenseIds.paymentPostingId

        assertFailureLeavesAggregateUnchanged(deposited, StagedPaymentViolation.DuplicateIdentity) {
            deposited.recordInstallment(
                fixture.catalog,
                fixture.finalCommand,
                fixture.finalIds.copy(
                    expenseIds = fixture.finalIds.expenseIds.copy(expensePostingId = existingAssetPostingId),
                ),
            )
        }
        assertFailureLeavesAggregateUnchanged(deposited, StagedPaymentViolation.DuplicateIdentity) {
            deposited.recordInstallment(
                fixture.catalog,
                fixture.finalCommand,
                fixture.finalIds.copy(
                    expenseIds = fixture.finalIds.expenseIds.copy(paymentPostingId = existingExpensePostingId),
                ),
            )
        }
    }

    @Test
    fun finalRequiresDepositAndStrictlyLaterActualAndSourceTimes() {
        assertEquals(
            StagedPaymentViolation.DepositRequired,
            failure(fixture.created().recordInstallment(fixture.catalog, fixture.finalCommand, fixture.finalIds)),
        )

        assertEquals(
            StagedPaymentViolation.FinalPaymentMustBeLaterThanDeposit,
            failure(
                fixture.deposited().recordInstallment(
                    fixture.catalog,
                    fixture.finalCommand.copy(actualPaymentAt = fixture.depositAt),
                    fixture.finalIds,
                ),
            ),
        )

        val sourcedDeposit = success(
            fixture.created().recordInstallment(
                fixture.catalog,
                fixture.depositCommand.copy(sourceTime = fixture.depositSourceTime()),
                fixture.depositIds,
            ),
        )
        assertEquals(
            StagedPaymentViolation.FinalSourcePaymentMustBeLaterThanDeposit,
            failure(
                sourcedDeposit.recordInstallment(
                    fixture.catalog,
                    fixture.finalCommand.copy(sourceTime = fixture.depositSourceTime()),
                    fixture.finalIds,
                ),
            ),
        )
    }

    @Test
    fun sourceTimePreservesExpectedOffsetTextAndCannotBeIllegallyConstructed() {
        val created = fixture.created()
        val sourceTime = fixture.depositSourceTime()
        val sourced = success(
            created.recordInstallment(
                fixture.catalog,
                fixture.depositCommand.copy(sourceTime = sourceTime),
                fixture.depositIds,
            ),
        )

        assertEquals(fixture.depositAt, sourceTime.instant)
        assertEquals(fixture.depositSourceText, sourceTime.text)
        assertEquals(sourceTime, sourced.installments.single().sourceTime)
        assertEquals(fixture.depositAt, sourced.installments.single().sourcePaymentAt)
        assertEquals(fixture.depositSourceText, sourced.installments.single().sourcePaymentAtText)
        assertEquals(fixture.depositAt, sourced.installments.single().statisticsAt)

        assertFailureLeavesAggregateUnchanged(created, StagedPaymentViolation.SourcePaymentOffsetMismatch) {
            StagedPaymentSourceTime.create(
                instant = fixture.depositAt,
                text = "2026-04-28T02:00:00Z",
                expectedOffsetText = fixture.expectedOffsetText,
            )
        }
        assertFailureLeavesAggregateUnchanged(created, StagedPaymentViolation.SourcePaymentTimeTextMismatch) {
            StagedPaymentSourceTime.create(
                instant = fixture.depositAt,
                text = "2026-04-28T10:00:01+08:00",
                expectedOffsetText = fixture.expectedOffsetText,
            )
        }
        assertFailureLeavesAggregateUnchanged(created, StagedPaymentViolation.InvalidSourcePaymentOffset) {
            StagedPaymentSourceTime.create(
                instant = fixture.depositAt,
                text = fixture.depositSourceText,
                expectedOffsetText = "+24:00",
            )
        }
        assertFailureLeavesAggregateUnchanged(created, StagedPaymentViolation.InvalidSourcePaymentTimestamp) {
            StagedPaymentSourceTime.create(
                instant = fixture.depositAt,
                text = "not-an-instant",
                expectedOffsetText = fixture.expectedOffsetText,
            )
        }
        assertFailureLeavesAggregateUnchanged(created, StagedPaymentViolation.InvalidSourcePaymentTimestamp) {
            StagedPaymentSourceTime.create(
                instant = fixture.depositAt,
                text = "2026-13-28T10:00:00+08:00",
                expectedOffsetText = fixture.expectedOffsetText,
            )
        }

        val utcInstant = Instant.parse("2026-04-28T02:00:00Z")
        assertEquals(
            "2026-04-28T02:00:00Z",
            success(
                StagedPaymentSourceTime.create(
                    instant = utcInstant,
                    text = "2026-04-28T02:00:00Z",
                    expectedOffsetText = "+00:00",
                ),
            ).text,
        )
        assertEquals(
            "2026-04-28T02:00:00+00:00",
            success(
                StagedPaymentSourceTime.create(
                    instant = utcInstant,
                    text = "2026-04-28T02:00:00+00:00",
                    expectedOffsetText = "Z",
                ),
            ).text,
        )
    }

    @Test
    fun rejectsInvalidTotalsInstallmentAmountsAndDueArithmetic() {
        assertEquals(
            StagedPaymentViolation.TotalAmountMustBePositive,
            failure(fixture.create(total = fixture.zero)),
        )
        assertEquals(
            StagedPaymentViolation.TotalAmountMustBePositive,
            failure(fixture.create(total = Money.ofMinor(-1, fixture.cny))),
        )
        assertEquals(
            StagedPaymentViolation.PaymentAmountMustBePositive,
            failure(
                fixture.created().recordInstallment(
                    fixture.catalog,
                    fixture.depositCommand.copy(amount = fixture.zero),
                    fixture.depositIds,
                ),
            ),
        )
        assertEquals(
            StagedPaymentViolation.PaymentAmountMustBePositive,
            failure(
                fixture.created().recordInstallment(
                    fixture.catalog,
                    fixture.depositCommand.copy(amount = Money.ofMinor(-1, fixture.cny)),
                    fixture.depositIds,
                ),
            ),
        )
        assertEquals(
            StagedPaymentViolation.DepositMustBeLessThanTotal,
            failure(
                fixture.created().recordInstallment(
                    fixture.catalog,
                    fixture.depositCommand.copy(amount = fixture.total),
                    fixture.depositIds,
                ),
            ),
        )
        assertEquals(
            StagedPaymentViolation.PaymentExceedsDue,
            failure(
                fixture.deposited().recordInstallment(
                    fixture.catalog,
                    fixture.finalCommand.copy(amount = Money.ofMinor(22_001, fixture.cny)),
                    fixture.finalIds,
                ),
            ),
        )
        assertEquals(
            StagedPaymentViolation.FinalMustEqualRemainingDue,
            failure(
                fixture.deposited().recordInstallment(
                    fixture.catalog,
                    fixture.finalCommand.copy(amount = Money.ofMinor(21_999, fixture.cny)),
                    fixture.finalIds,
                ),
            ),
        )
    }

    @Test
    fun rejectsInstallmentCurrencyThatDiffersFromLifecycleCurrency() {
        val usd = CurrencyUnit("USD", 2)

        assertEquals(
            StagedPaymentViolation.SingleCurrencyRequired,
            failure(
                fixture.created().recordInstallment(
                    fixture.catalog,
                    fixture.depositCommand.copy(amount = Money.ofMinor(8_000, usd)),
                    fixture.depositIds,
                ),
            ),
        )
    }

    @Test
    fun keepsAuthoritativeAppendOnlyHistoryWithExactSnapshots() {
        val created = fixture.created()
        val deposited = fixture.deposited()
        val fulfilled = fixture.fulfilled()
        val finalized = fixture.finalizedAfterFulfillment()
        val completed = fixture.completed()

        assertEquals(1, created.lifecycle.stateHistory.size)
        assertEquals(created.lifecycle.stateHistory, deposited.lifecycle.stateHistory.take(1))
        assertEquals(deposited.lifecycle.stateHistory, fulfilled.lifecycle.stateHistory.take(2))
        assertEquals(fulfilled.lifecycle.stateHistory, finalized.lifecycle.stateHistory.take(3))
        assertEquals(finalized.lifecycle.stateHistory, completed.lifecycle.stateHistory.take(4))
        assertEquals(listOf(1, 2, 3, 4, 5), completed.lifecycle.stateHistory.map { it.sequence })
        assertEquals(
            listOf(
                StagedPaymentEvent.GROUP_CREATED,
                StagedPaymentEvent.PAYMENT_CONFIRMED,
                StagedPaymentEvent.FULFILLMENT_CHANGED,
                StagedPaymentEvent.PAYMENT_CONFIRMED,
                StagedPaymentEvent.COMPLETION_CONFIRMED,
            ),
            completed.lifecycle.stateHistory.map { it.event },
        )
        assertEquals(listOf(null, fixture.depositPaymentId, null, fixture.finalPaymentId, null), completed.lifecycle.stateHistory.map { it.paymentId })
        assertTrue(completed.lifecycle.stateHistory.all { it.totalAmount == fixture.total })
        assertEquals(listOf(0L, 8_000L, 8_000L, 30_000L, 30_000L), completed.lifecycle.stateHistory.map { it.paidAmount.minorUnits })
        assertEquals(listOf(30_000L, 22_000L, 22_000L, 0L, 0L), completed.lifecycle.stateHistory.map { it.dueAmount.minorUnits })
        assertTrue(completed.lifecycle.stateHistory.all { it.stateTransitionEffectCount == 0 })
    }

    @Test
    fun fulfillmentAndCompletionAreStateOnlyWithZeroFinancialEffect() {
        val deposited = fixture.deposited()
        val fulfilled = success(
            deposited.changeFulfillment(
                historyId = StagedPaymentHistoryId("history-fulfilled"),
                fulfillment = StagedPaymentFulfillment.FULFILLED,
                occurredAt = fixture.fulfilledAt,
            ),
        )

        assertEquals(deposited.installments, fulfilled.installments)
        assertEquals(deposited.formalTransactions, fulfilled.formalTransactions)
        assertEquals(deposited.lifecycle.paidAmount, fulfilled.lifecycle.paidAmount)
        assertEquals(deposited.lifecycle.dueAmount, fulfilled.lifecycle.dueAmount)
        assertEquals(StagedPaymentProgress.PARTIALLY_PAID, fulfilled.paymentProgress)
        assertEquals(StagedPaymentFulfillment.FULFILLED, fulfilled.fulfillmentStatus)
        assertEquals(0, fulfilled.lifecycle.stateHistory.last().stateTransitionEffectCount)
        assertEquals(
            StagedPaymentViolation.InvalidFulfillmentTransition,
            failure(
                fulfilled.changeFulfillment(
                    historyId = StagedPaymentHistoryId("history-invalid-reversal"),
                    fulfillment = StagedPaymentFulfillment.IN_PROGRESS,
                    occurredAt = fixture.finalAt,
                ),
            ),
        )

        assertEquals(
            StagedPaymentViolation.CompletionRequiresPaidInFull,
            failure(
                fulfilled.confirmCompletion(
                    historyId = StagedPaymentHistoryId("history-too-early"),
                    occurredAt = fixture.finalAt,
                ),
            ),
        )

        val finalized = fixture.finalizedAfterFulfillment()
        val completed = success(
            finalized.confirmCompletion(
                historyId = StagedPaymentHistoryId("history-completed"),
                occurredAt = fixture.afterCompletionAt,
            ),
        )
        assertEquals(finalized.installments, completed.installments)
        assertEquals(finalized.formalTransactions, completed.formalTransactions)
        assertEquals(StagedPaymentEvent.COMPLETION_CONFIRMED, completed.lifecycle.stateHistory.last().event)
        assertEquals(0, completed.lifecycle.stateHistory.last().stateTransitionEffectCount)
    }

    @Test
    fun derivesReconciliationOnlyFromOwnedEligibleMatchedPaymentAssetFacts() {
        val created = fixture.created()
        val deposited = fixture.deposited()
        val finalized = fixture.finalized()
        val depositPosting = fixture.depositIds.expenseIds.paymentPostingId
        val finalPosting = fixture.finalIds.expenseIds.paymentPostingId
        val depositMatched = reconciliationFact(depositPosting, eligible = true, StagedPaymentReconciliationStatus.MATCHED)
        val finalMatched = reconciliationFact(finalPosting, eligible = true, StagedPaymentReconciliationStatus.MATCHED)

        assertEquals(StagedPaymentReconciliation.PENDING, success(created.reconciliation(emptyList())))
        assertEquals(StagedPaymentReconciliation.PENDING, success(deposited.reconciliation(emptyList())))
        assertEquals(
            StagedPaymentReconciliation.PENDING,
            success(deposited.reconciliation(listOf(reconciliationFact(depositPosting, eligible = false, StagedPaymentReconciliationStatus.MATCHED)))),
        )
        assertEquals(
            StagedPaymentReconciliation.PENDING,
            success(deposited.reconciliation(listOf(reconciliationFact(depositPosting, eligible = true, StagedPaymentReconciliationStatus.PENDING)))),
        )
        assertEquals(
            StagedPaymentReconciliation.PENDING,
            success(deposited.reconciliation(listOf(reconciliationFact(depositPosting, eligible = true, StagedPaymentReconciliationStatus.HAS_DIFFERENCE)))),
        )
        assertEquals(
            StagedPaymentReconciliation.PENDING,
            success(deposited.reconciliation(listOf(reconciliationFact(PostingId("unrelated"), eligible = true, StagedPaymentReconciliationStatus.MATCHED)))),
        )
        assertEquals(StagedPaymentReconciliation.COMPLETE, success(deposited.reconciliation(listOf(depositMatched))))
        assertEquals(StagedPaymentReconciliation.PENDING, success(finalized.reconciliation(emptyList())))
        assertEquals(StagedPaymentReconciliation.PARTIAL, success(finalized.reconciliation(listOf(depositMatched))))
        assertEquals(StagedPaymentReconciliation.PARTIAL, success(finalized.reconciliation(listOf(depositMatched, depositMatched))))
        assertEquals(StagedPaymentReconciliation.COMPLETE, success(finalized.reconciliation(listOf(depositMatched, finalMatched))))
        assertEquals(
            StagedPaymentViolation.ConflictingReconciliationFacts,
            failure(
                finalized.reconciliation(
                    listOf(
                        depositMatched,
                        reconciliationFact(depositPosting, eligible = true, StagedPaymentReconciliationStatus.PENDING),
                    ),
                ),
            ),
        )
    }

    @Test
    fun rejectsNonChronologicalStateHistoryWithoutChangingTheAggregate() {
        val deposited = fixture.deposited()

        assertEquals(
            StagedPaymentViolation.HistoryTimeMustIncrease,
            failure(
                deposited.changeFulfillment(
                    historyId = StagedPaymentHistoryId("history-not-later"),
                    fulfillment = StagedPaymentFulfillment.FULFILLED,
                    occurredAt = fixture.depositAt,
                ),
            ),
        )
        assertEquals(2, deposited.lifecycle.stateHistory.size)
        assertEquals(1, deposited.formalTransactions.size)
    }

    @Test
    fun keepsStagedViolationsOutsideTheSharedDomainViolationHierarchy() {
        val result: StagedPaymentResult<StagedPaymentSourceTime> =
            StagedPaymentSourceTime.create(
                instant = fixture.depositAt,
                text = fixture.depositSourceText,
                expectedOffsetText = "+24:00",
            )
        val violation = assertIs<StagedPaymentResult.Failure>(result).violation

        assertEquals(StagedPaymentViolation.InvalidSourcePaymentOffset, violation)
        assertTrue((violation as Any) !is DomainViolation)
    }

    @Test
    fun wrapsOrdinaryExpenseDependencyFailuresInsideTheStagedResultBoundary() {
        val inactiveCatalog = success(
            LedgerCatalog.create(
                accounts = fixture.catalog.accounts,
                categories = fixture.catalog.categories.map { category ->
                    if (category.id == fixture.categoryId) category.copy(active = false) else category
                },
            ),
        )

        assertEquals(
            StagedPaymentViolation.DependencyViolation(OrdinaryExpenseViolation.CategoryInactive),
            failure(
                fixture.created().recordInstallment(
                    inactiveCatalog,
                    fixture.depositCommand,
                    fixture.depositIds,
                ),
            ),
        )
    }
}

private fun reconciliationFact(
    postingId: PostingId,
    eligible: Boolean,
    status: StagedPaymentReconciliationStatus,
): StagedPaymentReconciliationFact =
    StagedPaymentReconciliationFact(postingId, eligible, status)

private fun assertFailureLeavesAggregateUnchanged(
    staged: StagedPayment,
    expected: StagedPaymentViolation,
    operation: () -> StagedPaymentResult<*>,
) {
    val members = staged.relation.memberRefs
    val history = staged.lifecycle.stateHistory
    val totalAmount = staged.lifecycle.totalAmount
    val paidAmount = staged.lifecycle.paidAmount
    val dueAmount = staged.lifecycle.dueAmount
    val installments = staged.installments
    val formalTransactions = staged.formalTransactions

    assertEquals(expected, failure(operation()))
    assertEquals(members, staged.relation.memberRefs)
    assertEquals(history, staged.lifecycle.stateHistory)
    assertEquals(totalAmount, staged.lifecycle.totalAmount)
    assertEquals(paidAmount, staged.lifecycle.paidAmount)
    assertEquals(dueAmount, staged.lifecycle.dueAmount)
    assertEquals(installments, staged.installments)
    assertEquals(formalTransactions, staged.formalTransactions)
}

private inline fun <reified T> success(result: StagedPaymentResult<T>): T =
    assertIs<StagedPaymentResult.Success<T>>(result).value

private fun failure(result: StagedPaymentResult<*>): StagedPaymentViolation =
    assertIs<StagedPaymentResult.Failure>(result).violation

private class StagedPaymentFixture {
    val ledgerId = LedgerId("ledger-a")
    val cny = CurrencyUnit("CNY", 2)
    val total = Money.ofMinor(30_000, cny)
    val zero = Money.ofMinor(0, cny)
    val depositAmount = Money.ofMinor(8_000, cny)
    val finalAmount = Money.ofMinor(22_000, cny)
    val fundingAccountId = AccountId("asset-bank-a")
    val expenseAccountId = AccountId("expense-account-service")
    val categoryId = CategoryId("expense-category-service")
    val relationId = StagedPaymentRelationId("relation-staged-payment")
    val lifecycleId = StagedPaymentLifecycleId("lifecycle-staged-payment")
    val depositPaymentId = InstallmentPaymentId("payment-deposit")
    val finalPaymentId = InstallmentPaymentId("payment-final")
    val createdAt = Instant.parse("2026-04-20T10:00:00+08:00")
    val depositAt = Instant.parse("2026-04-28T10:00:00+08:00")
    val depositSourceText = "2026-04-28T10:00:00+08:00"
    val expectedOffsetText = "+08:00"
    val fulfilledAt = Instant.parse("2026-05-01T12:00:00+08:00")
    val finalAt = Instant.parse("2026-05-03T16:30:00+08:00")
    val afterCompletionAt = Instant.parse("2026-05-04T10:00:00+08:00")

    val catalog = success(
        LedgerCatalog.create(
            accounts = listOf(
                Account(fundingAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
            ),
            categories = listOf(
                Category(CategoryId("expense-category-root"), ledgerId, null, null, active = true),
                Category(categoryId, ledgerId, CategoryId("expense-category-root"), expenseAccountId, active = true),
            ),
        ),
    )

    val creationIds = StagedPaymentCreationIds(
        relationId = relationId,
        lifecycleId = lifecycleId,
        historyId = StagedPaymentHistoryId("history-created"),
    )
    val depositCommand = RecordStagedPaymentInstallmentCommand(
        role = StagedPaymentRole.DEPOSIT,
        amount = depositAmount,
        fundingAccountId = fundingAccountId,
        actualPaymentAt = depositAt,
    )
    val finalCommand = RecordStagedPaymentInstallmentCommand(
        role = StagedPaymentRole.FINAL,
        amount = finalAmount,
        fundingAccountId = fundingAccountId,
        actualPaymentAt = finalAt,
    )
    val depositIds = paymentIds("deposit", StagedPaymentRole.DEPOSIT)
    val finalIds = paymentIds("final", StagedPaymentRole.FINAL)

    fun create(total: Money = this.total): StagedPaymentResult<StagedPayment> =
        createStagedPayment(
            catalog = catalog,
            command = CreateStagedPaymentCommand(ledgerId, total, categoryId, createdAt),
            ids = creationIds,
        )

    fun created(): StagedPayment = success(create())

    fun deposited(): StagedPayment =
        success(created().recordInstallment(catalog, depositCommand, depositIds))

    fun fulfilled(): StagedPayment =
        success(
            deposited().changeFulfillment(
                historyId = StagedPaymentHistoryId("history-fulfilled"),
                fulfillment = StagedPaymentFulfillment.FULFILLED,
                occurredAt = fulfilledAt,
            ),
        )

    fun finalized(): StagedPayment =
        success(deposited().recordInstallment(catalog, finalCommand, finalIds))

    fun finalizedAfterFulfillment(): StagedPayment =
        success(fulfilled().recordInstallment(catalog, finalCommand, finalIds))

    fun completed(): StagedPayment =
        success(
            finalizedAfterFulfillment().confirmCompletion(
                historyId = StagedPaymentHistoryId("history-completed"),
                occurredAt = afterCompletionAt,
            ),
        )

    fun depositSourceTime(): StagedPaymentSourceTime =
        success(
            StagedPaymentSourceTime.create(
                instant = depositAt,
                text = depositSourceText,
                expectedOffsetText = expectedOffsetText,
            ),
        )

    fun paymentIds(label: String, role: StagedPaymentRole): StagedPaymentInstallmentIds {
        val roleName = role.name.lowercase()
        return StagedPaymentInstallmentIds(
            paymentId = InstallmentPaymentId("payment-$label"),
            historyId = StagedPaymentHistoryId("history-$label"),
            expenseIds = AssetPaidOrdinaryExpenseIds(
                transactionId = TransactionId("tx-$roleName-$label"),
                versionId = TransactionVersionId("version-$roleName-$label"),
                postingSetId = PostingSetId("posting-set-$roleName-$label"),
                expensePostingId = PostingId("posting-expense-$roleName-$label"),
                paymentPostingId = PostingId("posting-asset-$roleName-$label"),
            ),
        )
    }
}
