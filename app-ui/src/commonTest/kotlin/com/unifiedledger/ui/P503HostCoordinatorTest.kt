package com.unifiedledger.ui

import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P5-04.4 S5: pure JVM hold of the [P503HostCoordinator] host-wiring decision skeleton
 * (T-C1..T-C5, spec section 7). Verifies RetryRefresh -> refresh, RetrySubmission reusing the
 * same draft/requestId, Created -> exactly one authoritative refresh, UnknownCommit entry ->
 * exactly one read-only check with `lastCheckOutcome` + per-instance guards.
 */
class P503HostCoordinatorTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val categoryId = CategoryId("expense-category-breakfast")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId = RequestId("request-uuid-v7-1")
    private val draft = ManualExpenseDraft(paymentAccountId, categoryId, "35.80", occurredAt)

    @Test
    fun retryRefreshInvokesRefreshCallback() {
        var refreshCalls = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshCalls += 1 },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )
        val readFailure = P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)

        assertEquals(HostAction.RetryRefresh, coordinator.retryRefresh(readFailure))
        assertEquals(1, refreshCalls)
    }

    @Test
    fun retrySubmissionReusesSameRequestId() {
        var receivedDraft: ManualExpenseDraft? = null
        var receivedRequestId: RequestId? = null
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { draft, requestId ->
                    receivedDraft = draft
                    receivedRequestId = requestId
                },
                onCheck = { _, _ -> error("unused") },
            )
        val submissionFailure =
            P503AppState.InfrastructureFailure(
                context = InfrastructureFailureContext.SUBMISSION,
                draft = draft,
                requestId = requestId,
            )

        assertEquals(
            HostAction.RetrySubmission(draft, requestId),
            coordinator.retrySubmission(submissionFailure),
        )
        assertEquals(draft, receivedDraft)
        assertEquals(requestId, receivedRequestId)
    }

    @Test
    fun createdTriggersAuthoritativeRefreshExactlyOnce() {
        var refreshCalls = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshCalls += 1 },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )

        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshCalls)

        // Same instance re-evaluated does not re-trigger.
        assertNull(coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshCalls)
    }

    @Test
    fun unknownCommitEntryTriggersReadOnlyCheckExactlyOnceThenGuardBlocks() {
        var checkCount = 0
        var receivedDraft: ManualExpenseDraft? = null
        var receivedRequestId: RequestId? = null
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { draft, requestId ->
                    checkCount += 1
                    receivedDraft = draft
                    receivedRequestId = requestId
                },
            )
        val entry = P503AppState.UnknownCommit(draft, requestId)

        assertEquals(HostAction.UnknownCheck(draft, requestId), coordinator.decide(entry))
        assertEquals(1, checkCount)
        assertEquals(draft, receivedDraft)
        assertEquals(requestId, receivedRequestId)

        // Same instance re-evaluated does not re-check.
        assertNull(coordinator.decide(entry))
        assertEquals(1, checkCount)

        // After ABSENT is recorded the guard blocks regardless of a different instance.
        val absent = P503AppState.UnknownCommit(draft, requestId, lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
        assertNull(coordinator.decide(absent))
        assertEquals(1, checkCount)
        val unavailable = P503AppState.UnknownCommit(draft, requestId, lastCheckOutcome = UnknownCommitCheckOutcome.UNAVAILABLE)
        assertNull(coordinator.decide(unavailable))
        assertEquals(1, checkCount)
    }

    @Test
    fun unknownCommitManualRetryStaysGuardedByIdempotent() {
        var checkCount = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> checkCount += 1 },
            )
        val entry = P503AppState.UnknownCommit(draft, requestId)

        // Manual re-check fires for the current instance.
        assertEquals(HostAction.UnknownCheck(draft, requestId), coordinator.retryCommitStatusCheck(entry))
        assertEquals(1, checkCount)

        // The per-instance marker prevents a duplicate manual trigger on the same instance.
        assertNull(coordinator.retryCommitStatusCheck(entry))
        assertEquals(1, checkCount)

        // The automatic decide path is now guarded too by the per-instance marker (still NONE).
        assertNull(coordinator.decide(entry))
        assertEquals(1, checkCount)

        // Recording an outcome keeps the automatic guard from re-triggering.
        val absent = P503AppState.UnknownCommit(draft, requestId, lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
        assertNull(coordinator.decide(absent))
        assertEquals(1, checkCount)
    }
}
