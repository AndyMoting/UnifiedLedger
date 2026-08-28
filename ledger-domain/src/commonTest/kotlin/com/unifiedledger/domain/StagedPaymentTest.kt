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
            formal.postingSets
                .single()
                .postings
                .map { it.amount.minorUnits },
        )
        assertEquals(
            listOf(fixture.expenseAccountId, fixture.fundingAccountId),
            formal.postingSets
                .single()
                .postings
                .map { it.accountId },
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
                formal.postingSets
                    .single()
                    .postings
                    .map { it.amount.minorUnits }
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
            staged.formalTransactions.map {
                it.versions
                    .single()
                    .times.statisticsAt
            },
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

        val sourcedDeposit =
            success(
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
        val sourced =
            success(
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
        val fulfilled =
            success(
                deposited.changeFulfillment(
                    historyId = StagedPaymentHistoryId("history-fulfilled"),
                    fulfillment = StagedPaymentFulfillment.FULFILLED,
                    occurredAt = fixture.fulfilledAt,
                ),
            )

        assertEquals(deposited.installments, fulfilled.installments)
        assertEquals(deposited.snapshot().formalTransactions, fulfilled.snapshot().formalTransactions)
        assertEquals(deposited.lifecycle.paidAmount, fulfilled.lifecycle.paidAmount)
        assertEquals(deposited.lifecycle.dueAmount, fulfilled.lifecycle.dueAmount)
        assertEquals(StagedPaymentProgress.PARTIALLY_PAID, fulfilled.paymentProgress)
        assertEquals(StagedPaymentFulfillment.FULFILLED, fulfilled.fulfillmentStatus)
        assertEquals(
            0,
            fulfilled.lifecycle.stateHistory
                .last()
                .stateTransitionEffectCount,
        )
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
        val completed =
            success(
                finalized.confirmCompletion(
                    historyId = StagedPaymentHistoryId("history-completed"),
                    occurredAt = fixture.afterCompletionAt,
                ),
            )
        assertEquals(finalized.installments, completed.installments)
        assertEquals(finalized.snapshot().formalTransactions, completed.snapshot().formalTransactions)
        assertEquals(
            StagedPaymentEvent.COMPLETION_CONFIRMED,
            completed.lifecycle.stateHistory
                .last()
                .event,
        )
        assertEquals(
            0,
            completed.lifecycle.stateHistory
                .last()
                .stateTransitionEffectCount,
        )
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
    fun rehydratesEveryReachableSnapshotWithoutCatalogOrCommandReplay() {
        val sourcedDeposit =
            success(
                fixture.created().recordInstallment(
                    fixture.catalog,
                    fixture.depositCommand.copy(sourceTime = fixture.depositSourceTime()),
                    fixture.depositIds,
                ),
            )
        val reachable =
            listOf(
                fixture.created(),
                sourcedDeposit,
                fixture.fulfilled(),
                fixture.finalized(),
                fixture.completed(),
            )

        reachable.forEach { original ->
            val restored = success(rehydrateStagedPayment(original.snapshot()))
            assertEquals(original.ledgerId, restored.ledgerId)
            assertEquals(original.relation.id, restored.relation.id)
            assertEquals(original.relation.memberRefs, restored.relation.memberRefs)
            assertEquals(original.lifecycle.id, restored.lifecycle.id)
            assertEquals(original.lifecycle.totalAmount, restored.lifecycle.totalAmount)
            assertEquals(original.lifecycle.paidAmount, restored.lifecycle.paidAmount)
            assertEquals(original.lifecycle.dueAmount, restored.lifecycle.dueAmount)
            assertEquals(original.lifecycle.stateHistory, restored.lifecycle.stateHistory)
            assertEquals(original.installments, restored.installments)
            assertEquals(
                original.formalTransactions.map { it.transaction },
                restored.formalTransactions.map { it.transaction },
            )
            assertEquals(
                original.formalTransactions.map { it.versions },
                restored.formalTransactions.map { it.versions },
            )
            assertEquals(
                original.formalTransactions.flatMap { it.postingSets }.flatMap { it.postings },
                restored.formalTransactions.flatMap { it.postingSets }.flatMap { it.postings },
            )
        }
    }

    @Test
    fun snapshotValidationReturnsDeterministicIndexedFailuresForEveryInvariantFamily() {
        val valid = fixture.finalized().snapshot()
        val relationFirst =
            valid.copy(
                relation = valid.relation.copy(memberRefs = emptyList()),
                lifecycle = valid.lifecycle.copy(paidAmount = fixture.zero),
            )
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.RELATION_MEMBERSHIP, null),
            failure(rehydrateStagedPayment(relationFirst)),
        )

        val lifecycleArithmetic =
            valid.copy(
                lifecycle = valid.lifecycle.copy(paidAmount = fixture.zero),
            )
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.LIFECYCLE_ARITHMETIC, null),
            failure(rehydrateStagedPayment(lifecycleArithmetic)),
        )

        val invalidHistory =
            valid.lifecycle.stateHistory.toMutableList().also {
                it[1] = it[1].copy(sequence = 7)
            }
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.HISTORY, 1),
            failure(rehydrateStagedPayment(valid.copy(lifecycle = valid.lifecycle.copy(stateHistory = invalidHistory)))),
        )

        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.INSTALLMENT, 0),
            failure(
                rehydrateStagedPayment(
                    valid.copy(
                        installments =
                            valid.installments.mapIndexed { index, payment ->
                                if (index == 0) payment.copy(statisticsAt = fixture.finalAt) else payment
                            },
                    ),
                ),
            ),
        )

        val invalidSource =
            fixture.deposited().snapshot().let { snapshot ->
                val payment = snapshot.installments.single()
                snapshot.copy(
                    installments =
                        listOf(
                            payment.copy(
                                sourcePaymentAt = fixture.depositAt,
                                sourcePaymentAtText = "not-an-instant",
                            ),
                        ),
                )
            }
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.SOURCE_TIME, 0),
            failure(rehydrateStagedPayment(invalidSource)),
        )

        val duplicateHistoryIdentity =
            valid.lifecycle.stateHistory.toMutableList().also {
                it[1] = it[1].copy(id = it[0].id)
            }
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.IDENTITY, 1),
            failure(rehydrateStagedPayment(valid.copy(lifecycle = valid.lifecycle.copy(stateHistory = duplicateHistoryIdentity)))),
        )
    }

    @Test
    fun invalidHistoryStructurePrecedesDuplicateHistoryIdentity() {
        val valid = fixture.finalized().snapshot()
        val competingHistory =
            valid.lifecycle.stateHistory.toMutableList().also {
                it[1] = it[1].copy(id = it[0].id, sequence = 7)
            }

        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.HISTORY, 1),
            failure(
                rehydrateStagedPayment(
                    valid.copy(lifecycle = valid.lifecycle.copy(stateHistory = competingHistory)),
                ),
            ),
        )
    }

    @Test
    fun invalidInstallmentStructurePrecedesDuplicateInstallmentAndFormalIdentity() {
        val valid = fixture.finalized().snapshot()
        val firstInstallment = valid.installments.first()
        val invalidInstallments =
            valid.installments.mapIndexed { index, payment ->
                when (index) {
                    0 -> payment.copy(statisticsAt = fixture.finalAt)
                    else ->
                        payment.copy(
                            transactionId = firstInstallment.transactionId,
                            expensePostingId = firstInstallment.expensePostingId,
                            assetPostingId = firstInstallment.assetPostingId,
                        )
                }
            }
        val firstFormalTransactionId =
            valid.formalTransactions
                .first()
                .transaction.id
        val duplicateFormalIdentity =
            valid.formalTransactions.mapIndexed { index, formal ->
                if (index == 0) formal else formal.copy(transaction = formal.transaction.copy(id = firstFormalTransactionId))
            }

        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.INSTALLMENT, 0),
            failure(
                rehydrateStagedPayment(
                    valid.copy(
                        installments = invalidInstallments,
                        formalTransactions = duplicateFormalIdentity,
                    ),
                ),
            ),
        )
    }

    @Test
    fun rehydrationPreservesRawRelationRowsAndRejectsDuplicatesBeforeConstructingTheDomainSet() {
        val valid = fixture.deposited().snapshot()
        val duplicateRows = valid.relation.memberRefs + valid.relation.memberRefs.last()
        val snapshot =
            valid.copy(
                relation = valid.relation.copy(memberRefs = duplicateRows),
            )

        assertEquals(3, snapshot.relation.memberRefs.size)
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.RELATION_MEMBERSHIP, 2),
            failure(rehydrateStagedPayment(snapshot)),
        )
    }

    @Test
    fun rehydrationIsCatalogFreeButLaterCommandsUseCurrentCatalogAdmission() {
        val historical = fixture.deposited()
        val driftedCatalog =
            success(
                LedgerCatalog.create(
                    accounts = fixture.catalog.accounts,
                    categories =
                        fixture.catalog.categories.map { category ->
                            if (category.id == fixture.categoryId) category.copy(active = false) else category
                        },
                ),
            )

        val restored = success(rehydrateStagedPayment(historical.snapshot()))
        assertEquals(historical.installments, restored.installments)
        assertEquals(
            StagedPaymentViolation.DependencyViolation(OrdinaryExpenseViolation.CategoryInactive),
            failure(restored.recordInstallment(driftedCatalog, fixture.finalCommand, fixture.finalIds)),
        )
    }

    @Test
    fun sourceTimeRehydrationIsStructuralAndDoesNotDependOnTheCurrentOffsetPolicy() {
        val sourced =
            success(
                fixture.created().recordInstallment(
                    fixture.catalog,
                    fixture.depositCommand.copy(sourceTime = fixture.depositSourceTime()),
                    fixture.depositIds,
                ),
            )
        val snapshot = sourced.snapshot()
        val payment = snapshot.installments.single()
        val utcTextSnapshot =
            snapshot.copy(
                installments = listOf(payment.copy(sourcePaymentAtText = "2026-04-28T02:00:00Z")),
            )

        val restored = success(rehydrateStagedPayment(utcTextSnapshot))
        assertEquals(fixture.depositAt, restored.installments.single().sourcePaymentAt)
        assertEquals("2026-04-28T02:00:00Z", restored.installments.single().sourcePaymentAtText)
    }

    @Test
    fun rehydrationUsesTheFormalFactoryAndRequiresTheCurrentPostingSetToMatchTheInstallment() {
        val deposited = fixture.deposited()
        val snapshot = deposited.snapshot()
        val original = snapshot.formalTransactions.single()
        val replacementVersionId = TransactionVersionId("version-deposit-2")
        val metadataReplacement =
            original.copy(
                transaction = original.transaction.copy(currentVersionId = replacementVersionId),
                versions =
                    original.versions +
                        original.versions.single().copy(
                            id = replacementVersionId,
                            versionNumber = 2,
                            note = "corrected note",
                        ),
                postingSets = original.postingSets,
            )
        assertEquals(
            2,
            success(rehydrateStagedPayment(snapshot.copy(formalTransactions = listOf(metadataReplacement))))
                .formalTransactions
                .single()
                .versions.size,
        )

        val replacementSet =
            StagedPaymentPostingSetSnapshot(
                PostingSetId("posting-set-deposit-2"),
                listOf(
                    StagedPaymentPostingSnapshot(
                        PostingId("posting-expense-deposit-2"),
                        fixture.expenseAccountId,
                        fixture.depositAmount,
                    ),
                    StagedPaymentPostingSnapshot(
                        PostingId("posting-asset-deposit-2"),
                        fixture.fundingAccountId,
                        Money.ofMinor(-fixture.depositAmount.minorUnits, fixture.cny),
                    ),
                ),
            )
        val mismatchedReplacement =
            original.copy(
                transaction = original.transaction.copy(currentVersionId = replacementVersionId),
                versions =
                    original.versions +
                        original.versions.single().copy(
                            id = replacementVersionId,
                            versionNumber = 2,
                            postingSetId = replacementSet.id,
                        ),
                postingSets = original.postingSets + replacementSet,
            )
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, 0),
            failure(rehydrateStagedPayment(snapshot.copy(formalTransactions = listOf(mismatchedReplacement)))),
        )
    }

    @Test
    fun rehydratedAggregateRejectsInstallmentThatReusesAHistoricalPostingIdentity() {
        val snapshot = fixture.deposited().snapshot()
        val original = snapshot.formalTransactions.single()
        val originalVersion = original.versions.single()
        val currentPostingSet = original.postingSets.single()
        val historicalExpensePostingId = PostingId("posting-expense-deposit-historical")
        val historicalPostingSet =
            StagedPaymentPostingSetSnapshot(
                PostingSetId("posting-set-deposit-historical"),
                listOf(
                    StagedPaymentPostingSnapshot(
                        historicalExpensePostingId,
                        fixture.expenseAccountId,
                        fixture.depositAmount,
                    ),
                    StagedPaymentPostingSnapshot(
                        PostingId("posting-asset-deposit-historical"),
                        fixture.fundingAccountId,
                        Money.ofMinor(-fixture.depositAmount.minorUnits, fixture.cny),
                    ),
                ),
            )
        val currentVersionId = TransactionVersionId("version-deposit-current")
        val multiVersionFormal =
            original.copy(
                transaction = original.transaction.copy(currentVersionId = currentVersionId),
                versions =
                    listOf(
                        originalVersion.copy(postingSetId = historicalPostingSet.id),
                        originalVersion.copy(
                            id = currentVersionId,
                            versionNumber = 2,
                            postingSetId = currentPostingSet.id,
                        ),
                    ),
                postingSets = listOf(historicalPostingSet, currentPostingSet),
            )
        val restored =
            success(
                rehydrateStagedPayment(snapshot.copy(formalTransactions = listOf(multiVersionFormal))),
            )
        val collidingIds =
            fixture.finalIds.copy(
                expenseIds =
                    fixture.finalIds.expenseIds.copy(
                        expensePostingId = historicalExpensePostingId,
                    ),
            )

        assertFailureLeavesAggregateUnchanged(restored, StagedPaymentViolation.DuplicateIdentity) {
            restored.recordInstallment(fixture.catalog, fixture.finalCommand, collidingIds)
        }
    }

    @Test
    fun malformedPostingSetsAndFormalChainsReturnDeterministicFormalTransactionFailures() {
        val valid = fixture.deposited().snapshot()
        val formal = valid.formalTransactions.single()
        val postingSet = formal.postingSets.single()
        val malformedPostingSet =
            formal.copy(
                postingSets = listOf(postingSet.copy(postings = postingSet.postings.take(1))),
            )
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.FORMAL_TRANSACTION, 0),
            failure(rehydrateStagedPayment(valid.copy(formalTransactions = listOf(malformedPostingSet)))),
        )

        val malformedChain =
            formal.copy(
                versions =
                    listOf(
                        formal.versions.single().copy(postingSetId = PostingSetId("missing-posting-set")),
                    ),
            )
        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.FORMAL_TRANSACTION, 0),
            failure(rehydrateStagedPayment(valid.copy(formalTransactions = listOf(malformedChain)))),
        )
    }

    @Test
    fun installmentAndFormalTransactionOrderMustMatchOneToOne() {
        val valid = fixture.finalized().snapshot()

        assertEquals(
            StagedPaymentViolation.InvalidSnapshot(StagedPaymentSnapshotProblem.FORMAL_LINKAGE, 0),
            failure(rehydrateStagedPayment(valid.copy(formalTransactions = valid.formalTransactions.reversed()))),
        )
    }

    @Test
    fun snapshotAndRehydratedAggregateDefensivelyCopyCollections() {
        val original = fixture.deposited()
        val members = original.relation.memberRefs.toMutableList()
        val history = original.lifecycle.stateHistory.toMutableList()
        val installments = original.snapshot().installments.toMutableList()
        val originalFormal = original.snapshot().formalTransactions.single()
        val versions = originalFormal.versions.toMutableList()
        val postingSets = originalFormal.postingSets.toMutableList()
        val postings = postingSets.single().postings.toMutableList()
        postingSets[0] = postingSets.single().copy(postings = postings)
        val formalTransactions =
            mutableListOf(
                originalFormal.copy(versions = versions, postingSets = postingSets),
            )
        val snapshot =
            StagedPaymentSnapshot(
                ledgerId = original.ledgerId,
                relation = StagedPaymentRelationSnapshot(original.relation.id, members),
                lifecycle =
                    StagedPaymentLifecycleSnapshot(
                        original.lifecycle.id,
                        original.lifecycle.totalAmount,
                        original.lifecycle.paidAmount,
                        original.lifecycle.dueAmount,
                        original.lifecycle.currency,
                        original.lifecycle.categoryId,
                        history,
                    ),
                installments = installments,
                formalTransactions = formalTransactions,
            )
        members.clear()
        history.clear()
        installments.clear()
        formalTransactions.clear()
        versions.clear()
        postingSets.clear()
        postings.clear()

        assertEquals(original.relation.memberRefs, snapshot.relation.memberRefs.toSet())
        assertEquals(original.lifecycle.stateHistory, snapshot.lifecycle.stateHistory)
        assertEquals(1, snapshot.installments.size)
        assertEquals(1, snapshot.formalTransactions.size)
        (snapshot.installments as MutableList).clear()
        (snapshot.formalTransactions as MutableList).clear()
        (snapshot.formalTransactions.single().versions as MutableList).clear()
        (snapshot.formalTransactions.single().postingSets as MutableList).clear()
        (
            snapshot.formalTransactions
                .single()
                .postingSets
                .single()
                .postings as MutableList
        ).clear()
        assertEquals(1, snapshot.installments.size)
        assertEquals(1, snapshot.formalTransactions.size)
        assertEquals(
            1,
            snapshot.formalTransactions
                .single()
                .versions.size,
        )
        assertEquals(
            1,
            snapshot.formalTransactions
                .single()
                .postingSets.size,
        )
        assertEquals(
            2,
            snapshot.formalTransactions
                .single()
                .postingSets
                .single()
                .postings.size,
        )

        val restored = success(rehydrateStagedPayment(snapshot))
        val leakedMembers = restored.relation.memberRefs as MutableSet
        leakedMembers.clear()
        val leakedHistory = restored.lifecycle.stateHistory as MutableList
        leakedHistory.clear()
        val leakedInstallments = restored.installments as MutableList
        leakedInstallments.clear()
        val leakedFormals = restored.formalTransactions as MutableList
        leakedFormals.clear()
        clearIfMutable(restored.formalTransactions.single().versions)
        clearIfMutable(restored.formalTransactions.single().postingSets)
        clearIfMutable(
            restored.formalTransactions
                .single()
                .postingSets
                .single()
                .postings,
        )
        assertEquals(2, restored.relation.memberRefs.size)
        assertEquals(2, restored.lifecycle.stateHistory.size)
        assertEquals(1, restored.installments.size)
        assertEquals(1, restored.formalTransactions.size)
        assertEquals(
            1,
            restored.formalTransactions
                .single()
                .versions.size,
        )
        assertEquals(
            1,
            restored.formalTransactions
                .single()
                .postingSets.size,
        )
        assertEquals(
            2,
            restored.formalTransactions
                .single()
                .postingSets
                .single()
                .postings.size,
        )
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
        val inactiveCatalog =
            success(
                LedgerCatalog.create(
                    accounts = fixture.catalog.accounts,
                    categories =
                        fixture.catalog.categories.map { category ->
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
): StagedPaymentReconciliationFact = StagedPaymentReconciliationFact(postingId, eligible, status)

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
    val formalTransactions = staged.snapshot().formalTransactions

    assertEquals(expected, failure(operation()))
    assertEquals(members, staged.relation.memberRefs)
    assertEquals(history, staged.lifecycle.stateHistory)
    assertEquals(totalAmount, staged.lifecycle.totalAmount)
    assertEquals(paidAmount, staged.lifecycle.paidAmount)
    assertEquals(dueAmount, staged.lifecycle.dueAmount)
    assertEquals(installments, staged.installments)
    assertEquals(formalTransactions, staged.snapshot().formalTransactions)
}

private fun clearIfMutable(values: List<*>) {
    try {
        (values as MutableList<*>).clear()
    } catch (_: ClassCastException) {
        // A non-mutable list implementation is already isolated from callers.
    } catch (_: UnsupportedOperationException) {
        // A read-only backing implementation is also acceptable at this boundary.
    }
}

private inline fun <reified T> success(result: StagedPaymentResult<T>): T = assertIs<StagedPaymentResult.Success<T>>(result).value

private fun failure(result: StagedPaymentResult<*>): StagedPaymentViolation = assertIs<StagedPaymentResult.Failure>(result).violation

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

    val catalog =
        success(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(fundingAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                        Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                    ),
                categories =
                    listOf(
                        Category(CategoryId("expense-category-root"), ledgerId, null, null, active = true),
                        Category(categoryId, ledgerId, CategoryId("expense-category-root"), expenseAccountId, active = true),
                    ),
            ),
        )

    val creationIds =
        StagedPaymentCreationIds(
            relationId = relationId,
            lifecycleId = lifecycleId,
            historyId = StagedPaymentHistoryId("history-created"),
        )
    val depositCommand =
        RecordStagedPaymentInstallmentCommand(
            role = StagedPaymentRole.DEPOSIT,
            amount = depositAmount,
            fundingAccountId = fundingAccountId,
            actualPaymentAt = depositAt,
        )
    val finalCommand =
        RecordStagedPaymentInstallmentCommand(
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

    fun deposited(): StagedPayment = success(created().recordInstallment(catalog, depositCommand, depositIds))

    fun fulfilled(): StagedPayment =
        success(
            deposited().changeFulfillment(
                historyId = StagedPaymentHistoryId("history-fulfilled"),
                fulfillment = StagedPaymentFulfillment.FULFILLED,
                occurredAt = fulfilledAt,
            ),
        )

    fun finalized(): StagedPayment = success(deposited().recordInstallment(catalog, finalCommand, finalIds))

    fun finalizedAfterFulfillment(): StagedPayment = success(fulfilled().recordInstallment(catalog, finalCommand, finalIds))

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

    fun paymentIds(
        label: String,
        role: StagedPaymentRole,
    ): StagedPaymentInstallmentIds {
        val roleName = role.name.lowercase()
        return StagedPaymentInstallmentIds(
            paymentId = InstallmentPaymentId("payment-$label"),
            historyId = StagedPaymentHistoryId("history-$label"),
            expenseIds =
                AssetPaidOrdinaryExpenseIds(
                    transactionId = TransactionId("tx-$roleName-$label"),
                    versionId = TransactionVersionId("version-$roleName-$label"),
                    postingSetId = PostingSetId("posting-set-$roleName-$label"),
                    expensePostingId = PostingId("posting-expense-$roleName-$label"),
                    paymentPostingId = PostingId("posting-asset-$roleName-$label"),
                ),
        )
    }
}
