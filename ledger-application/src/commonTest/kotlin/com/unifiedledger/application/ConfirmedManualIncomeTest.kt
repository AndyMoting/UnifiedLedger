package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.unifiedledger.domain.*
import kotlin.time.Instant

class ConfirmedManualIncomeTest {
    @Test fun `sparse missing input does not call confirmed commit`() {
        var calls = 0
        val confirmed = ExecuteConfirmedManualIncome(ConfirmedManualIncomeCommitPort { _, _, _ -> calls++; error("must not commit") }, ConfirmedManualIncomeIdSource { error("must not allocate") }, ConfirmedIncomeTransactionFactory { _, _ -> error("must not create") })
        val result = ExecuteManualIncomeSave(confirmed).execute(ManualIncomeSaveInput(LedgerId("ledger"), RequestId("request"), null, null, null, Instant.parse("2026-01-01T00:00:00Z"), "", ExplicitManualSave))
        assertIs<ManualIncomeSaveResult.InvalidInput>(result)
        assertEquals(0, calls)
    }
    @Test fun `same snapshot replays and different snapshot conflicts`() {
        val ledger = LedgerId("ledger"); val currency = CurrencyUnit("CNY", 2)
        val receipt = ConfirmedIncomeReceipt(ConfirmationId("confirmation"), TransactionId("tx"))
        var calls = 0
        var first: ManualIncomeRequestSnapshot? = null
        val port = ConfirmedManualIncomeCommitPort { identity, snapshot, callback ->
            if (first == null) { first = snapshot; calls++; ConfirmedManualIncomeResult.Created(receipt) }
            else if (first == snapshot) ConfirmedManualIncomeResult.NoChange(receipt)
            else ConfirmedManualIncomeResult.RequestIdentityConflict(identity)
        }
        val execute = ExecuteConfirmedManualIncome(port, ConfirmedManualIncomeIdSource { error("ids not needed") }, ConfirmedIncomeTransactionFactory { _, _ -> error("factory not needed") })
        val request = ExplicitlyConfirmedManualIncome(ledger, RequestId("request"), Money.ofMinor(1, currency), CategoryId("income"), AccountId("asset"), Instant.parse("2026-01-01T00:00:00Z"), "", ExplicitManualSave)
        assertIs<ConfirmedManualIncomeResult.Created>(execute.execute(request))
        assertIs<ConfirmedManualIncomeResult.NoChange>(execute.execute(request))
        assertIs<ConfirmedManualIncomeResult.RequestIdentityConflict>(execute.execute(request.copy(note = "x")))
        assertEquals(1, calls)
    }
}
