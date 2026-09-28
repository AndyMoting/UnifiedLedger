package com.unifiedledger.application

import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 07.B (D-184 item 3) budget configuration application contract: canonical
 * snapshot/fingerprint shape and the use-case typed rejections that need no persistence.
 *
 * The `inputFingerprint` is derived from the snapshot and is explicitly NOT part of
 * equivalent-replay identity; the snapshot is the sole basis. A negative limit is a typed
 * rejection before any claim; a category scope must resolve to an EXPENSE category.
 */
class BudgetConfigurationTest {
    private val ledgerId = LedgerId("ledger-budget-test")
    private val march = YearMonth(2026, 3)

    @Test
    fun theCanonicalSnapshotIsStableAndTheFingerprintIsDerivedFromIt() {
        val setLimit = BudgetCommandPayload.SetLimit(12_345L)
        assertEquals("{\"command\":\"SetBudgetLimit\",\"limit_minor\":\"12345\"}", canonicalBudgetRequestSnapshot(setLimit))
        // The same payload always yields the same bytes, and the fingerprint is the digest of them.
        assertEquals(canonicalBudgetRequestSnapshot(setLimit), canonicalBudgetRequestSnapshot(BudgetCommandPayload.SetLimit(12_345L)))
        assertEquals(budgetInputFingerprint(canonicalBudgetRequestSnapshot(setLimit)), budgetInputFingerprint(canonicalBudgetRequestSnapshot(BudgetCommandPayload.SetLimit(12_345L))))
        // A frozen SHA-256 vector of the exact canonical bytes: if the snapshot spelling ever
        // changes, this digest changes and the equivalent-replay identity silently breaks.
        assertEquals(
            "sha256:7defdb1775c286695dcd0c60c3da7a62f697b8183228227c0d41c48e9089ab74",
            budgetInputFingerprint(canonicalBudgetRequestSnapshot(setLimit)),
        )
        // The close command is a distinct snapshot (no limit field).
        assertEquals("{\"command\":\"CloseBudget\"}", canonicalBudgetRequestSnapshot(BudgetCommandPayload.Close))
        assertTrue(canonicalBudgetRequestSnapshot(BudgetCommandPayload.Close) != canonicalBudgetRequestSnapshot(setLimit))
        // The fingerprint is derived, so a distinct snapshot always has a distinct digest.
        assertTrue(
            budgetInputFingerprint(canonicalBudgetRequestSnapshot(BudgetCommandPayload.Close)) !=
                budgetInputFingerprint(canonicalBudgetRequestSnapshot(setLimit)),
        )
    }

    @Test
    fun aNegativeLimitIsRejectedBeforeAnyCommit() {
        val recorder = RecordingCommitPort()
        val useCase = useCase(recorder)
        val result = useCase.setLimit(ledgerId, march, BudgetScope.Total, -1L, expectedRevision = 0L)
        val rejected = assertIs<BudgetCommandResult.Rejected>(result)
        assertEquals(BudgetFailureCode.BUDGET_LIMIT_NEGATIVE, rejected.failureCode)
        // The typed rejection is produced before the port is ever called.
        assertEquals(0, recorder.requests.size)
    }

    @Test
    fun aZeroLimitIsAcceptedByTheUseCaseAsAMonitoredBudget() {
        val recorder = RecordingCommitPort()
        val useCase = useCase(recorder)
        useCase.setLimit(ledgerId, march, BudgetScope.Total, 0L, expectedRevision = 0L)
        assertEquals(1, recorder.requests.size)
        val payload = recorder.requests.single().command
        assertEquals(BudgetCommandPayload.SetLimit(0L), payload)
    }

    @Test
    fun aCategoryScopeOutsideTheCatalogIsRejectedBeforeAnyCommit() {
        val recorder = RecordingCommitPort()
        val useCase = useCase(recorder, catalog = emptyCatalog())
        val result = useCase.setLimit(ledgerId, march, BudgetScope.Category(CategoryId("category-absent")), 100L, expectedRevision = 0L)
        val rejected = assertIs<BudgetCommandResult.Rejected>(result)
        assertEquals(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID, rejected.failureCode)
        assertEquals(0, recorder.requests.size)
    }

    @Test
    fun anIncomeCategoryScopeIsRejectedBecauseOnlyExpenseCategoriesBudget() {
        val recorder = RecordingCommitPort()
        val useCase = useCase(recorder, catalog = catalogWithOneIncomeCategory())
        val result = useCase.setLimit(ledgerId, march, BudgetScope.Category(CategoryId("category-salary")), 100L, expectedRevision = 0L)
        val rejected = assertIs<BudgetCommandResult.Rejected>(result)
        assertEquals(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID, rejected.failureCode)
        // The kind check is a typed rejection before the port is ever called.
        assertEquals(0, recorder.requests.size)
    }

    @Test
    fun anUnknownLedgerRejectsACategoryScopeButStillAcceptsATotalScope() {
        val recorder = RecordingCommitPort()
        val useCase =
            SaveBudgetConfiguration(
                commitPort = recorder,
                requestIdSource = BudgetRequestIdSource { BudgetRequestId("request-1") },
                budgetIdSource = BudgetIdSource { com.unifiedledger.domain.BudgetId("budget-1") },
                catalogReader = CatalogAuthorityReader { null },
                clock = LedgerClock { Instant.parse("2026-03-05T02:00:00Z") },
            )
        // A category scope cannot resolve without a catalog, so it is rejected with zero calls.
        val rejected = assertIs<BudgetCommandResult.Rejected>(
            useCase.setLimit(ledgerId, march, BudgetScope.Category(CategoryId("category-food")), 100L, expectedRevision = 0L),
        )
        assertEquals(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID, rejected.failureCode)
        assertEquals(0, recorder.requests.size)
        // A TOTAL scope needs no catalog at all, so it still reaches the commit port.
        useCase.setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L)
        assertEquals(1, recorder.requests.size)
    }

    @Test
    fun theBudgetViolationFailureCodeMappingIsStable() {
        // Mirrors CatalogFailureCode.of: the domain budget violations map to their frozen codes
        // and any other domain violation falls back to the generic constraint code.
        assertEquals(BudgetFailureCode.BUDGET_LIMIT_NEGATIVE, BudgetFailureCode.of(com.unifiedledger.domain.BudgetViolation.LimitNegative))
        assertEquals(BudgetFailureCode.BUDGET_SCOPE_UNSUPPORTED, BudgetFailureCode.of(com.unifiedledger.domain.BudgetViolation.ScopeUnsupported))
        assertEquals(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID, BudgetFailureCode.of(com.unifiedledger.domain.BudgetViolation.ScopeCategoryInvalid))
        assertEquals(BudgetFailureCode.BUDGET_CURRENCY_UNSUPPORTED, BudgetFailureCode.of(com.unifiedledger.domain.BudgetViolation.CurrencyUnsupported))
        assertEquals(
            BudgetFailureCode.BUDGET_CONSTRAINT_VIOLATION,
            BudgetFailureCode.of(com.unifiedledger.domain.CatalogViolation.CatalogNameEmpty),
        )
    }

    @Test
    fun theScopeAndMonthKeysAreCanonical() {
        assertEquals("TOTAL", budgetScopeKey(BudgetScope.Total))
        assertEquals("CATEGORY:category-food", budgetScopeKey(BudgetScope.Category(CategoryId("category-food"))))
        assertEquals("2026-03", budgetMonthKey(march))
        assertEquals("2026-11", budgetMonthKey(YearMonth(2026, 11)))
    }

    private fun useCase(
        port: BudgetConfigurationCommitPort,
        catalog: LedgerCatalog = emptyCatalog(),
    ): SaveBudgetConfiguration =
        SaveBudgetConfiguration(
            commitPort = port,
            requestIdSource = BudgetRequestIdSource { BudgetRequestId("request-1") },
            budgetIdSource = BudgetIdSource { com.unifiedledger.domain.BudgetId("budget-1") },
            catalogReader = CatalogAuthorityReader { requested -> if (requested == ledgerId) CatalogAuthority(requested, catalog, 1L) else null },
            clock = LedgerClock { Instant.parse("2026-03-05T02:00:00Z") },
        )

    private fun emptyCatalog(): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts = emptyList(), categories = emptyList())) {
            is com.unifiedledger.domain.DomainResult.Success -> result.value
            is com.unifiedledger.domain.DomainResult.Failure -> error("empty catalog must be valid")
        }

    private fun catalogWithOneIncomeCategory(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts = emptyList(),
                    categories =
                        listOf(
                            Category(
                                id = CategoryId("category-salary"),
                                ledgerId = ledgerId,
                                parentId = null,
                                postingAccountId = null,
                                active = true,
                                kind = CategoryKind.INCOME,
                                name = "salary",
                            ),
                        ),
                )
        ) {
            is com.unifiedledger.domain.DomainResult.Success -> result.value
            is com.unifiedledger.domain.DomainResult.Failure -> error("income catalog must be valid")
        }

    private class RecordingCommitPort : BudgetConfigurationCommitPort {
        val requests = mutableListOf<BudgetCommandRequest>()

        override fun commitOnce(
            request: BudgetCommandRequest,
            mintBudgetId: () -> com.unifiedledger.domain.BudgetId,
        ): BudgetCommandResult {
            requests.add(request)
            return BudgetCommandResult.Accepted(
                BudgetCommandReceipt(
                    requestId = request.requestId,
                    outcome = BudgetReceiptOutcome.ACCEPTED,
                    budgetId = mintBudgetId(),
                    newRevision = request.expectedRevision + 1L,
                ),
            )
        }
    }
}
