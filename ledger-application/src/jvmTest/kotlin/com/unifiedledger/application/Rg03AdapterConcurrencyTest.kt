package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Rg03AdapterConcurrencyTest {
    @Test
    fun `invalid adaptations are reentrant and never exchange errors`() {
        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(validRg03Raw())).value
        val base = decoded.operations.first()
        val context = Rg03AdapterContext(LedgerId("ledger-a"), CurrencyUnit("CNY", 2))
        val invalidId = base.copy(input = base.input.copy(requestId = Rg03JsonField.Value("")))
        val invalidAmount = base.copy(input = base.input.copy(sourceDebitAmount = Rg03JsonField.Value("60.001")))
        val expectedId = Rg03ContractError("$.input.request_id", Rg03ContractErrorReason.INVALID_ID)
        val expectedAmount = Rg03ContractError("$.input.source_debit_amount", Rg03ContractErrorReason.INVALID_DECIMAL)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks =
                List(20_000) { index ->
                    Callable {
                        val (operation, expected) = if (index % 2 == 0) invalidId to expectedId else invalidAmount to expectedAmount
                        expected to assertIs<Rg03AdaptResult.Invalid>(adaptRg03Operation(context, operation)).error
                    }
                }
            pool.invokeAll(tasks).forEach { future ->
                val (expected, actual) = future.get()
                assertEquals(expected, actual)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
