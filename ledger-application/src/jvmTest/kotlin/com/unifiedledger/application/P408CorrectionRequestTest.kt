package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-113 request contract acceptance (spec section 4/Appendix B, TP-15 at the
 * application layer): request shape validation, the v2-only correction family
 * fingerprint, and output-id exclusion for equivalent retries. Pure logic; no
 * database.
 */
class P408CorrectionRequestTest {
    @Test
    fun checkedCorrectionRequiresCompleteSuccessorAndProjectionQuartet() {
        assertFailsWith<IllegalArgumentException> {
            invalidateOnlyBase().copy(resultState = P408CorrectionResultState.CHECKED)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(projectionId = null)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(projectionRuleVersion = null)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(normalizedAmountMinor = null)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(rawAmountMinor = null)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(rawCurrencyPrecision = null)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(successorLinkId = null)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(successorCreatedAt = null)
        }
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(reconciliationId = null)
        }
    }

    @Test
    fun invalidateOnlyCarriesNoSuccessorOrProjectionFields() {
        val request = invalidateOnlyBase()
        assertEquals(P408CorrectionResultState.MISSING, request.resultState)
        assertEquals(null, request.successor)
        assertEquals(null, request.projectionId)
        assertEquals(null, request.successorLinkId)
        // A missing/DIFFERENCE correction still names the affected reconciliation.
        assertEquals("reconciliation-posting-a", request.reconciliationId)

        assertFailsWith<IllegalArgumentException> {
            invalidateOnlyBase().copy(successor = successorFacts())
        }
        assertFailsWith<IllegalArgumentException> {
            invalidateOnlyBase().copy(projectionId = "proj-evidence-a")
        }
        assertFailsWith<IllegalArgumentException> {
            invalidateOnlyBase().copy(resultState = P408CorrectionResultState.DIFFERENCE, successorLinkId = "link-b")
        }
    }

    @Test
    fun affectedPostingMustBeTheSuccessorPostingWhenChecked() {
        assertFailsWith<IllegalArgumentException> {
            checkedBase().copy(affectedPostingId = "posting-other")
        }
    }

    @Test
    fun fingerprintIsDeterministicAndExcludesOutputIds() {
        val request = checkedBase()
        assertEquals(request.fingerprint(), request.fingerprint())

        // Output/generated id changes do not alter the identity: equivalent retry.
        assertEquals(
            request.fingerprint(),
            request
                .copy(
                    successorLinkId = "link-b-other",
                    successorCreatedAt = "2026-08-10T16:00:00+08:00",
                    reconciliationId = "reconciliation-other",
                ).fingerprint(),
        )
        // Semantic changes do.
        val invariant = request.fingerprint()
        assertFalse(invariant == request.copy(reason = P408CorrectionReason.CORRECTED).fingerprint())
        assertFalse(invariant == request.copy(confirmedAt = "2026-08-10T15:00:00+08:00").fingerprint())
        // The invalidation-only family lives in a disjoint semantic space.
        assertFalse(invariant == invalidateOnlyBase().fingerprint())
    }

    @Test
    fun correctionFingerprintSpaceIsDisjointFromConfirmFamily() {
        assertTrue(checkedBase().fingerprint().startsWith("p408-correct-v2|"))
        // A valid confirm-family request uses its own v2 prefix.
        assertTrue(confirmFingerprintSample().startsWith("p408-confirm-v2|"))
    }

    private fun checkedBase() =
        P408CorrectLinkRequest(
            ledgerId = "ledger-a",
            requestId = "correction-a",
            evidenceId = "evidence-a",
            previousLinkId = "link-a",
            reason = P408CorrectionReason.POSTING_REPLACED,
            affectedPostingId = "posting-a2",
            resultState = P408CorrectionResultState.CHECKED,
            successor = successorFacts(),
            projectionId = "proj-evidence-a-2",
            projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
            projectionRuleVersion = 1,
            normalizedAmountMinor = 1000,
            rawAmountMinor = 1000,
            rawCurrencyPrecision = 2,
            confirmedAt = "2026-08-10T14:00:00+08:00",
            successorLinkId = "link-b",
            successorCreatedAt = "2026-08-10T14:00:00+08:00",
            reconciliationId = "reconciliation-posting-a2",
        )

    private fun invalidateOnlyBase() =
        P408CorrectLinkRequest(
            ledgerId = "ledger-a",
            requestId = "correction-missing",
            evidenceId = "evidence-a",
            previousLinkId = "link-a",
            reason = P408CorrectionReason.CORRECTED,
            affectedPostingId = "posting-a",
            resultState = P408CorrectionResultState.MISSING,
            confirmedAt = "2026-08-10T14:00:00+08:00",
            reconciliationId = "reconciliation-posting-a",
        )

    private fun successorFacts() =
        P408SuccessorLinkFacts(
            postingId = "posting-a2",
            transactionId = "tx-a",
            amountMinor = 1000,
            currencyCode = "CNY",
            currencyPrecision = 2,
            direction = "out",
            accountId = "account-bank-a",
            responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
            candidateId = "candidate-transient-a2",
            matchBasis = REQUIRED_MATCH_BASIS,
            windowDays = 2,
            naturalDayDistance = 0,
            sourceOccurredAt = "2026-08-10T12:00:00+08:00",
        )

    /** Minimal confirm-family fingerprint for the disjointness probe. */
    private fun confirmFingerprintSample(): String =
        P408ConfirmLinkRequest(
            ledgerId = "ledger-a",
            requestId = "request-a",
            evidenceId = "evidence-a",
            candidateId = "candidate-transient-a",
            postingId = "posting-a",
            transactionId = "tx-a",
            amountMinor = 1000,
            currencyCode = "CNY",
            currencyPrecision = 2,
            direction = "out",
            accountId = "account-bank-a",
            responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
            basisVersion = 2,
            matchBasis = REQUIRED_MATCH_BASIS,
            windowDays = 2,
            naturalDayDistance = 0,
            sourceOccurredAt = "2026-08-10T12:00:00+08:00",
            confirmedAt = "2026-08-10T13:00:00+08:00",
            linkId = "link-a",
            reconciliationId = "reconciliation-posting-a",
            createdAt = "2026-08-10T13:00:00+08:00",
            projectionId = "proj-evidence-a",
            projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
            projectionRuleVersion = 1,
            normalizedAmountMinor = 1000,
            rawAmountMinor = 1000,
            rawCurrencyPrecision = 2,
        ).fingerprint()
}
