package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class Rg07OperationsTest {
    private val ledger = LedgerId("ledger-a")
    private val currency = CurrencyUnit("CNY", 2)
    private val case =
        Rg07AdaptedCase(
            ledger,
            currency,
            assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(emptyList(), emptyList())).value,
        )

    @Test fun manualExpenseAdapterRejectsNonPositiveAndWrongCurrency() {
        val base =
            Rg07ManualExpenseInput(
                RequestId("request"),
                Money.ofMinor(12_000, currency),
                CategoryId("daily"),
                AccountId("bank"),
                Instant.parse("2026-01-10T00:00:00Z"),
                "",
                true,
            )
        assertEquals(
            Rg07RejectionReason.MUST_BE_POSITIVE,
            (adaptRg07ManualExpense(case, base.copy(amount = Money.ofMinor(0, currency))) as Rg07AdaptResult.Invalid).reason,
        )
        assertEquals(
            Rg07RejectionReason.SAME_CURRENCY_REQUIRED,
            (adaptRg07ManualExpense(case, base.copy(amount = Money.ofMinor(12000, CurrencyUnit("USD", 2)))) as Rg07AdaptResult.Invalid).reason,
        )
        assertIs<Rg07AdaptResult.Success>(adaptRg07ManualExpense(case, base))
    }

    @Test fun statusAdapterRequiresAtLeastOneRecordedTime() {
        val base =
            Rg07StatusInput(
                RequestId("request"),
                TransactionId("original"),
                Money.ofMinor(3_000, currency),
                null,
                null,
                null,
            )
        assertEquals(
            Rg07RejectionReason.INVALID_TIMESTAMP,
            (adaptRg07Status(case, base) as Rg07AdaptResult.Invalid).reason,
        )
        assertIs<Rg07AdaptResult.Success>(
            adaptRg07Status(case, base.copy(processorReportedAt = Instant.parse("2026-01-23T00:00:00Z"))),
        )
    }

    @Test fun statusSourceUsesTypedRefundStatus() {
        val accepted =
            adaptRg07StatusSource(
                case,
                Rg07StatusSourceInput("source", "relation", Instant.parse("2026-01-23T00:00:00Z"), Rg07StatusSourceState.PROCESSING, false),
            )
        assertIs<Rg07AdaptResult.Success>(accepted)
        assertEquals(Rg07Action.INGEST_REFUND_STATUS_SOURCE, (accepted as Rg07AdaptResult.Success).operation.action)
    }

    @Test fun dualRoleEvidenceRequiresValidRoleSet() {
        val good =
            Rg07DualRoleEvidenceInput(
                "source",
                "evidence",
                "relation",
                "posting",
                Instant.parse("2026-02-02T10:00:00Z"),
                listOf("refund_relationship", "destination_asset_posting"),
            )
        assertIs<Rg07AdaptResult.Success>(adaptRg07DualRoleEvidence(case, good))
        val bad = good.copy(roles = listOf("unknown_role"))
        assertEquals(
            Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH,
            (adaptRg07DualRoleEvidence(case, bad) as Rg07AdaptResult.Invalid).reason,
        )
    }

    @Test fun originalPaymentEvidencePreservesSignedAssetAmountThroughExecution() {
        val input =
            Rg07OriginalPaymentEvidenceInput(
                "source-original-payment",
                "evidence-original-payment",
                "posting-original-payment",
                Money.ofMinor(-12_000, currency),
                Instant.parse("2026-01-10T00:00:00Z"),
                Instant.parse("2026-01-10T00:01:00Z"),
                Instant.parse("2026-01-10T00:02:00Z"),
                "sha256:original-payment",
            )
        val adapted = assertIs<Rg07AdaptResult.Success>(adaptRg07OriginalPaymentEvidence(case, input))
        var committed: Rg07Operation? = null
        val result =
            ExecuteRg07Operation { operation ->
                committed = operation
                Rg07ExecutionResult.Accepted()
            }.execute(adapted.operation)

        assertIs<Rg07ExecutionResult.Accepted>(result)
        assertEquals(-12_000, assertIs<Rg07Operation.OriginalPaymentEvidence>(committed).input.amount.minorUnits)
    }

    @Test fun importConfirmationAdapterChecksCurrencyAndPreservesNullableBindings() {
        val complete =
            Rg07ImportConfirmationInput(
                "request",
                "candidate-refund-rg07-import",
                TransactionId("original"),
                CategoryId("daily"),
                Money.ofMinor(3_000, currency),
                AccountId("wallet"),
                Instant.parse("2026-02-02T07:20:00Z"),
                Instant.parse("2026-02-02T10:00:00Z"),
                true,
            )
        val adapted = adaptRg07ImportConfirmation(case, complete, Rg07OperationIdentity(ledger, "op"))
        assertIs<Rg07AdaptResult.Success>(adapted)
        assertEquals(Rg07Action.CONFIRM_IMPORTED_REFUND, (adapted as Rg07AdaptResult.Success).operation.action)
        // Missing binding fields stay null in the typed payload; the store layer
        // rejects them against the candidate (not synthesized by the adapter).
        val missingCategory = complete.copy(categoryId = null)
        val missing = adaptRg07ImportConfirmation(case, missingCategory, Rg07OperationIdentity(ledger, "op"))
        assertIs<Rg07AdaptResult.Success>(missing)
        val missingOperation = (missing as Rg07AdaptResult.Success).operation as Rg07Operation.ImportConfirm
        assertEquals(null, missingOperation.input.categoryId)
        // Cross-currency allocation is rejected at the adapter boundary.
        val crossCurrency = complete.copy(allocatedAmount = Money.ofMinor(3000, CurrencyUnit("USD", 2)))
        assertEquals(
            Rg07RejectionReason.SAME_CURRENCY_REQUIRED,
            (adaptRg07ImportConfirmation(case, crossCurrency, Rg07OperationIdentity(ledger, "op")) as Rg07AdaptResult.Invalid).reason,
        )
    }

    @Test fun validateAdapterIsStructuralAndDefersSemanticsToStore() {
        val base =
            Rg07ValidateInput(
                "attempt",
                TransactionId("original"),
                Money.ofMinor(3_000, currency),
                CategoryId("daily"),
                AccountId("wallet"),
                true,
                Money.ofMinor(12_000, currency),
            )
        // Cross-currency is a store-level rejection (expected has a rejected
        // cross-currency validate operation); the structural adapter passes it.
        assertIs<Rg07AdaptResult.Success>(adaptRg07Validate(case, base.copy(amount = Money.ofMinor(3000, CurrencyUnit("USD", 2)))))
        assertIs<Rg07AdaptResult.Success>(adaptRg07Validate(case, base))
    }

    @Test fun allocateAdapterChecksBothAmounts() {
        val base = Rg07AllocateInput("candidate", Money.ofMinor(3_000, currency), Money.ofMinor(9_000, currency))
        assertIs<Rg07AdaptResult.Success>(adaptRg07Allocate(case, base))
        assertEquals(
            Rg07RejectionReason.SAME_CURRENCY_REQUIRED,
            (adaptRg07Allocate(case, base.copy(requestedAllocation = Money.ofMinor(3000, CurrencyUnit("USD", 2)))) as Rg07AdaptResult.Invalid).reason,
        )
    }

    @Test fun lifecycleTransitionOrderIsEnforced() {
        assertTrue(isValidRg07StatusTransition(null, Rg07RefundStatus.REQUESTED))
        assertTrue(isValidRg07StatusTransition(Rg07RefundStatus.REQUESTED, Rg07RefundStatus.APPROVED))
        assertTrue(isValidRg07StatusTransition(Rg07RefundStatus.APPROVED, Rg07RefundStatus.PROCESSING))
        assertTrue(isValidRg07StatusTransition(Rg07RefundStatus.PROCESSING, Rg07RefundStatus.RECEIVED))
        assertEquals(false, isValidRg07StatusTransition(null, Rg07RefundStatus.RECEIVED))
        assertEquals(false, isValidRg07StatusTransition(Rg07RefundStatus.RECEIVED, Rg07RefundStatus.PROCESSING))
    }

    @Test fun dualRoleRegistryIsClosed() {
        assertTrue(isValidRg07DualRoleSet(listOf("refund_relationship", "destination_asset_posting")))
        assertEquals(false, isValidRg07DualRoleSet(listOf("payment_asset_posting", "destination_asset_posting")))
        assertEquals(false, isValidRg07DualRoleSet(listOf("refund_relationship", "destination_asset_posting", "refund_relationship")))
        assertEquals(false, isValidRg07DualRoleSet(emptyList()))
    }

    @Test fun dualRoleFingerprintCanonicalizesSetOrder() {
        val forward =
            Rg07Operation.DualRoleEvidence(
                ledger,
                Rg07DualRoleEvidenceInput(
                    "source",
                    "evidence",
                    "relation",
                    "posting",
                    Instant.parse("2026-02-02T10:00:00Z"),
                    listOf("refund_relationship", "destination_asset_posting"),
                ),
            )
        val reverse = forward.copy(input = forward.input.copy(roles = forward.input.roles.reversed()))
        assertEquals(forward.fingerprint(), reverse.fingerprint())
    }
}
