package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CreditPrincipalRepaymentIds
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MixedPaymentExpenseIds
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Rg04RawJsonDecoderTest {
    @Test
    fun rejectsEscapedDuplicateKeyWithTypedPath() {
        val decoded = decodeRg04RawJson("""{"case":{},"ca\u0073e":{}}""")
        val error = assertIs<Rg04RawJsonDecodeResult.Invalid>(decoded).error
        assertEquals(Rg04RawJsonContractErrorReason.DUPLICATE_KEY, error.reason)
        assertEquals("$.case", error.fieldPath)
    }

    @Test
    fun rejectsOversizeAndTooDeepDocumentsBeforeMapping() {
        assertEquals(
            Rg04RawJsonContractErrorReason.RESOURCE_LIMIT,
            assertIs<Rg04RawJsonDecodeResult.Invalid>(decodeRg04RawJson(" ".repeat(1_048_577))).error.reason,
        )
        val deep = "[".repeat(65) + "0" + "]".repeat(65)
        assertEquals(
            Rg04RawJsonContractErrorReason.RESOURCE_LIMIT,
            assertIs<Rg04RawJsonDecodeResult.Invalid>(decodeRg04RawJson(deep)).error.reason,
        )
    }

    @Test
    fun malformedOccurredAtIsATypedAdapterError() {
        val currency = CurrencyUnit("CNY", 2)
        val ledgerId = LedgerId("ledger-a")
        val catalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    listOf(
                        Account(AccountId("asset"), ledgerId, AccountKind.ASSET, currency, true, true),
                        Account(AccountId("liability"), ledgerId, AccountKind.LIABILITY, currency, true, true),
                        Account(AccountId("expense"), ledgerId, AccountKind.EXPENSE, currency, false, false),
                    ),
                    listOf(
                        Category(CategoryId("root"), ledgerId, null, null, true),
                        Category(CategoryId("daily"), ledgerId, CategoryId("root"), AccountId("expense"), true),
                    ),
                ),
            ).value
        val case =
            Rg04RawJsonCase(
                ledgerId,
                currency,
                "Asia/Shanghai",
                catalog,
                emptyList(),
                emptyList(),
                MixedPaymentExpenseIds(
                    TransactionId("tx"),
                    TransactionVersionId("version"),
                    PostingSetId("set"),
                    PostingId("expense-posting"),
                    listOf(PostingId("asset-posting"), PostingId("liability-posting")),
                ),
                CreditPrincipalRepaymentIds(
                    TransactionId("repayment-tx"),
                    TransactionVersionId("repayment-version"),
                    PostingSetId("repayment-set"),
                    PostingId("repayment-asset-posting"),
                    PostingId("repayment-liability-posting"),
                ),
                "relation",
                "Mixed payment",
            )
        val input =
            Rg04ManualInput(
                Rg04Field.Value("request"),
                Rg04Field.Value("2026-02-10T12:00:00"),
                Rg04Field.Value("120.00"),
                Rg04Field.Value("CNY"),
                Rg04Field.Value("daily"),
                listOf(
                    Rg04FundingInput(Rg04Field.Value("asset"), Rg04Field.Value("70.00"), Rg04Field.Value("CNY")),
                    Rg04FundingInput(Rg04Field.Value("liability"), Rg04Field.Value("50.00"), Rg04Field.Value("CNY")),
                ),
                Rg04SettlementInput("135.00", "15.00", "120.00"),
                Rg04Field.Value(true),
            )

        assertEquals(
            Rg04AdaptResult.Invalid("invalid_timestamp", "occurred_at"),
            adaptRg04Operation(
                case,
                Rg04DecodedOperation.Manual(
                    input,
                    Rg04Expected.Accepted,
                    Rg04OperationSource("$.manual_lifecycle.ordered_operations[*]", "request", "manual-purchase"),
                    Rg04OperationClass.CREATION,
                ),
            ),
        )
    }
}
