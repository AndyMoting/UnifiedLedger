package com.unifiedledger.application

import com.unifiedledger.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Rg05OperationsTest {
    @Test
    fun confirmationGuardAppliesOnlyToFormalCreationActions() {
        val currency = CurrencyUnit("CNY", 2)
        val ledger = LedgerId("ledger-a")
        val confirm = Rg05PreparedOperation.Confirm(
            Rg05ConfirmSnapshot(ledger, RequestId("request"), "candidate", AccountId("asset"), kotlin.time.Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00Z", kotlin.time.Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00Z", listOf(Rg05ConfirmAllocation("a", CategoryId("daily"), Money.ofMinor(400, currency)), Rg05ConfirmAllocation("b", CategoryId("service"), Money.ofMinor(600, currency))), false, "confirmed-status"),
            MergedPaymentExpenseIds(TransactionId("tx"), TransactionVersionId("version"), PostingSetId("set"), listOf(PostingId("expense-a"), PostingId("expense-b")), PostingId("asset-posting")), "relation", "confirmation", "reconciliation", "bank-link", emptyMap(), emptyMap(), emptyMap(),
        )
        val rejected = ExecuteRg05Operation { error("commit must not be called") }.execute(confirm)
        assertEquals(Rg05ExecutionResult.Rejected(Rg05ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "explicit_confirmation"), rejected)

        val ingest = Rg05PreparedOperation.Ingest(Rg05IngestSnapshot(ledger, RequestId("ingest"), Rg05BankFact("bank", "bank-evidence", kotlin.time.Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00Z", "bank", Money.ofMinor(-1_000, currency)), emptyList(), "candidate", "pending"))
        val accepted = Rg05ExecutionResult.IngestAccepted("candidate", emptyList(), emptyList())
        assertEquals(accepted, ExecuteRg05Operation { accepted }.execute(ingest))
    }
    @Test
    fun decoderRejectsDuplicateKeysAndUnknownFields() {
        val duplicate = decodeRg05RawJson("{\"schema_version\":1,\"schema_version\":1}")
        assertEquals(Rg05RawJsonContractErrorReason.DUPLICATE_KEY, assertIs<Rg05RawJsonDecodeResult.Invalid>(duplicate).error.reason)
        val unknown = decodeRg05RawJson("{\"schema_version\":1,\"unexpected\":true}")
        assertEquals(Rg05RawJsonContractErrorReason.UNKNOWN_FIELD, assertIs<Rg05RawJsonDecodeResult.Invalid>(unknown).error.reason)
    }

    @Test
    fun manualInputAdaptsExactAmountsAndRequiresExplicitConfirmation() {
        val currency = CurrencyUnit("CNY", 2)
        val ledger = LedgerId("ledger-a")
        val category = CategoryId("category-a")
        val catalog = assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("asset"), ledger, AccountKind.ASSET, currency, true, true),
                    Account(AccountId("expense"), ledger, AccountKind.EXPENSE, currency, false, false),
                ),
                listOf(Category(CategoryId("root"), ledger, null, null, true), Category(category, ledger, CategoryId("root"), AccountId("expense"), true)),
            ),
        ).value
        val input = Rg05ManualInput(
            Rg05Field.Value("request"), Rg05Field.Value("2026-04-10T10:30:00Z"), Rg05Field.Value("10.00"),
            Rg05Field.Value("CNY"), Rg05Field.Value("asset"),
            listOf(
                Rg05ItemInput(Rg05Field.Value("a"), Rg05Field.Value("4.00"), Rg05Field.Value("CNY"), Rg05Field.Value(category.value), Rg05Field.Value("daily"), Rg05Field.Value("2026-04-10T09:00:00Z")),
                Rg05ItemInput(Rg05Field.Value("b"), Rg05Field.Value("6.00"), Rg05Field.Value("CNY"), Rg05Field.Value(category.value), Rg05Field.Value("service"), Rg05Field.Value("2026-04-10T09:05:00Z")),
            ),
            Rg05Field.Value(false),
        )
        val case = Rg05RawJsonCase(ledger, currency, "Asia/Shanghai", catalog, input)
        val adapted = assertIs<Rg05AdaptResult.Success>(adaptRg05Manual(case, input, Rg05PreparedIds(
            MergedPaymentExpenseIds(TransactionId("tx"), TransactionVersionId("version"), PostingSetId("set"), listOf(PostingId("expense-a"), PostingId("expense-b")), PostingId("asset-posting")), "relation", "confirmation", "reconciliation")))
        val result = ExecuteRg05Operation { error("commit must not be called") }.execute(adapted.operation)
        assertEquals(Rg05ExecutionResult.Rejected(Rg05ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "explicit_confirmation"), result)
    }
}
