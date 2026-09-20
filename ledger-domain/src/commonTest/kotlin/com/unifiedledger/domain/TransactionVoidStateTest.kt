package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-05.C domain vocabulary evidence (spec sections 3.3/4.1; acceptance vectors V-22 and the
 * domain half of V-23). The effective predicate has exactly one domain representation here;
 * the SQL twin lives in `transaction_effective_state` and is compared against this type by
 * the ledger-data V-23 vector.
 */
class TransactionVoidStateTest {
    @Test
    fun noFactMeansEffective() {
        assertTrue(TransactionVoidState.effective.isEffective)
        assertNull(TransactionVoidState.effective.latestFactKind)
        assertEquals(0, TransactionVoidState.effective.depth)
        assertTrue(TransactionVoidState.of(emptyList()).isEffective)
    }

    @Test
    fun voidMakesTheTransactionIneffectiveAndRestoreBringsItBack() {
        val voided = TransactionVoidState.of(listOf(TransactionVoidFact(1, TransactionVoidFactKind.VOID)))
        assertFalse(voided.isEffective)
        assertEquals(TransactionVoidFactKind.VOID, voided.latestFactKind)
        assertEquals(1, voided.depth)

        val restored =
            TransactionVoidState.of(
                listOf(
                    TransactionVoidFact(1, TransactionVoidFactKind.VOID),
                    TransactionVoidFact(2, TransactionVoidFactKind.RESTORE),
                ),
            )
        assertTrue(restored.isEffective)
        assertEquals(TransactionVoidFactKind.RESTORE, restored.latestFactKind)
        assertEquals(2, restored.depth)
    }

    @Test
    fun factsAreOrderedBySequenceNotByCallerOrder() {
        val unordered =
            TransactionVoidState.of(
                listOf(
                    TransactionVoidFact(2, TransactionVoidFactKind.RESTORE),
                    TransactionVoidFact(1, TransactionVoidFactKind.VOID),
                ),
            )
        assertTrue(unordered.isEffective)
        assertEquals(listOf(1, 2), unordered.facts.map { it.sequence })
    }

    @Test
    fun appendRejectionFreezesTheSingleVoidPlusSingleRestoreDepth() {
        // First void on an effective, never-voided transaction.
        assertNull(voidAppendRejection(TransactionVoidState.effective, TransactionVoidFactKind.VOID))
        val voided = TransactionVoidState.of(listOf(TransactionVoidFact(1, TransactionVoidFactKind.VOID)))
        // A second void on a voided transaction is rejected (DP-8: restore first).
        assertEquals(
            P705FailureCode.P705_TRANSACTION_VOIDED,
            voidAppendRejection(voided, TransactionVoidFactKind.VOID),
        )
        // A restore of a voided transaction is admissible.
        assertNull(voidAppendRejection(voided, TransactionVoidFactKind.RESTORE))
        val restored =
            TransactionVoidState.of(
                listOf(
                    TransactionVoidFact(1, TransactionVoidFactKind.VOID),
                    TransactionVoidFact(2, TransactionVoidFactKind.RESTORE),
                ),
            )
        // After a restore, a further void exhausts the slice's cycle.
        assertEquals(
            P705FailureCode.P705_VOID_CYCLE_EXHAUSTED,
            voidAppendRejection(restored, TransactionVoidFactKind.VOID),
        )
        // A restore of an already restored transaction is rejected.
        assertEquals(
            P705FailureCode.P705_TRANSACTION_NOT_VOIDED,
            voidAppendRejection(restored, TransactionVoidFactKind.RESTORE),
        )
        // A restore of a never-voided transaction is rejected.
        assertEquals(
            P705FailureCode.P705_TRANSACTION_NOT_VOIDED,
            voidAppendRejection(TransactionVoidState.effective, TransactionVoidFactKind.RESTORE),
        )
    }

    @Test
    fun reasonStorageTokensRoundTripAndStayClosed() {
        assertEquals(
            listOf("mis_entered", "duplicate_entry", "no_longer_applicable", "voided_in_error", "other"),
            VoidReasonCode.entries.map { it.storageValue },
        )
        VoidReasonCode.entries.forEach { code ->
            assertEquals(code, VoidReasonCode.fromStorage(code.storageValue))
        }
        assertNull(VoidReasonCode.fromStorage("invented"))
        assertEquals(
            listOf("void", "restore"),
            TransactionVoidFactKind.entries.map { it.storageValue },
        )
        TransactionVoidFactKind.entries.forEach { kind ->
            assertEquals(kind, TransactionVoidFactKind.fromStorage(kind.storageValue))
        }
        assertNull(TransactionVoidFactKind.fromStorage("cancelled"))
    }

    @Test
    fun reasonRejectionRequiresACodeAndBoundsTheNote() {
        assertNull(voidReasonRejection(VoidReason(VoidReasonCode.MIS_ENTERED)))
        assertNull(voidReasonRejection(VoidReason(VoidReasonCode.MIS_ENTERED, "typed the wrong amount")))
        assertEquals(P705FailureCode.P705_VOID_REASON_REQUIRED, voidReasonRejection(null))
        assertEquals(
            P705FailureCode.P705_VOID_REASON_REQUIRED,
            voidReasonRejection(VoidReason(VoidReasonCode.OTHER, "   ")),
        )
        assertEquals(
            P705FailureCode.P705_VOID_REASON_REQUIRED,
            voidReasonRejection(VoidReason(VoidReasonCode.OTHER, "x".repeat(VOID_REASON_NOTE_MAX_LENGTH + 1))),
        )
        assertNull(voidReasonRejection(VoidReason(VoidReasonCode.OTHER, "x".repeat(VOID_REASON_NOTE_MAX_LENGTH))))
        assertEquals(200, VOID_REASON_NOTE_MAX_LENGTH)
    }

    @Test
    fun failureCodesCarryTheirStableTokens() {
        assertEquals("P705_TRANSACTION_NOT_FOUND", P705FailureCode.P705_TRANSACTION_NOT_FOUND.code)
        assertEquals("P705_STALE_CURRENT_VERSION", P705FailureCode.P705_STALE_CURRENT_VERSION.code)
        assertEquals("P705_VOID_CYCLE_EXHAUSTED", P705FailureCode.P705_VOID_CYCLE_EXHAUSTED.code)
        assertEquals("P705_MATCHED_FUNDING_LEG_CHANGED", P705FailureCode.P705_MATCHED_FUNDING_LEG_CHANGED.code)
        assertEquals("P705_REQUEST_IDENTITY_CONFLICT", P705FailureCode.P705_REQUEST_IDENTITY_CONFLICT.code)
        assertEquals(15, P705FailureCode.entries.size)
    }
}
