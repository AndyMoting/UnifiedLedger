package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class Rg02ManualIncomeAdapterTest {
    private val context =
        Rg02ManualIncomeContext(
            ledgerId = LedgerId("ledger-a"),
            currency = CurrencyUnit("CNY", 2),
            caseTimeZone = "Asia/Shanghai",
        )

    @Test
    fun `strict decoded input becomes exact typed save input`() {
        val parsed =
            assertIs<Rg02ManualIncomeAdaptResult.Success>(
                adaptRg02ManualIncomeInput(context, input()),
            ).value

        assertEquals(300_000L, parsed.saveInput.amount?.minorUnits)
        assertEquals(context.currency, parsed.saveInput.amount?.currency)
        assertEquals("request-rg02-create", parsed.saveInput.requestId.value)
        assertEquals("income-category-salary", parsed.saveInput.categoryId?.value)
        assertEquals("asset-bank-a", parsed.saveInput.receivingAccountId?.value)
        assertEquals(Instant.parse("2026-01-16T01:00:00Z"), parsed.saveInput.occurredAt)
        assertEquals("3000.00", parsed.originalAmountText)
        assertEquals("2026-01-16T09:00:00+08:00", parsed.originalOccurredAtText)
    }

    @Test
    fun `currency mismatch malformed decimal excess precision and overflow are typed errors`() {
        val cases =
            listOf(
                input().copy(currency = Rg02JsonField.Value("USD")) to
                    Rg02ManualIncomeContractError("$.input.currency", Rg02ManualIncomeContractErrorReason.CURRENCY_MISMATCH),
                input().copy(amount = Rg02JsonField.Value("three")) to
                    Rg02ManualIncomeContractError("$.input.amount", Rg02ManualIncomeContractErrorReason.INVALID_DECIMAL),
                input().copy(amount = Rg02JsonField.Value("1.001")) to
                    Rg02ManualIncomeContractError("$.input.amount", Rg02ManualIncomeContractErrorReason.INVALID_DECIMAL),
                input().copy(amount = Rg02JsonField.Value("92233720368547758.08")) to
                    Rg02ManualIncomeContractError("$.input.amount", Rg02ManualIncomeContractErrorReason.INVALID_DECIMAL),
            )

        cases.forEach { (candidate, expected) ->
            assertEquals(
                expected,
                assertIs<Rg02ManualIncomeAdaptResult.InvalidContract>(
                    adaptRg02ManualIncomeInput(context, candidate),
                ).error,
            )
        }
    }

    @Test
    fun `exact parser supports long boundaries while preserving domain validation values`() {
        val maximum =
            assertIs<Rg02ManualIncomeAdaptResult.Success>(
                adaptRg02ManualIncomeInput(context, input(amount = "92233720368547758.07")),
            ).value
        val minimum =
            assertIs<Rg02ManualIncomeAdaptResult.Success>(
                adaptRg02ManualIncomeInput(context, input(amount = "-92233720368547758.08")),
            ).value

        assertEquals(Long.MAX_VALUE, maximum.saveInput.amount?.minorUnits)
        assertEquals(Long.MIN_VALUE, minimum.saveInput.amount?.minorUnits)
    }

    @Test
    fun `malformed timestamps offsets and unsupported timezone are typed errors`() {
        val malformed =
            assertIs<Rg02ManualIncomeAdaptResult.InvalidContract>(
                adaptRg02ManualIncomeInput(
                    context,
                    input(occurredAt = "2026-01-16T09:00:00"),
                ),
            ).error
        assertEquals(Rg02ManualIncomeContractErrorReason.INVALID_TIMESTAMP, malformed.reason)

        val offset =
            assertIs<Rg02ManualIncomeAdaptResult.InvalidContract>(
                adaptRg02ManualIncomeInput(
                    context,
                    input(occurredAt = "2026-01-16T08:00:00+07:00"),
                ),
            ).error
        assertEquals(Rg02ManualIncomeContractErrorReason.TIMEZONE_OFFSET_MISMATCH, offset.reason)

        val timezone =
            assertIs<Rg02ManualIncomeAdaptResult.InvalidContract>(
                adaptRg02ManualIncomeInput(
                    context.copy(caseTimeZone = "Etc/UTC", validNumericOffset = "+00:00"),
                    input(occurredAt = "2026-01-16T01:00:00Z"),
                ),
            ).error
        assertEquals(Rg02ManualIncomeContractErrorReason.UNSUPPORTED_TIMEZONE, timezone.reason)
    }

    @Test
    fun `ledger request category and account ids are validated without throwing`() {
        val cases =
            listOf(
                Triple(context.copy(ledgerId = LedgerId("")), input(), "$.case.ledger_id"),
                Triple(context, input().copy(requestId = Rg02JsonField.Value("")), "$.input.request_id"),
                Triple(context, input().copy(categoryId = Rg02JsonField.Value("bad\u0001id")), "$.input.category_id"),
                Triple(context, input().copy(receivingAccountId = Rg02JsonField.Value("")), "$.input.receiving_account_id"),
            )

        cases.forEach { (candidateContext, candidateInput, path) ->
            val error =
                assertIs<Rg02ManualIncomeAdaptResult.InvalidContract>(
                    adaptRg02ManualIncomeInput(candidateContext, candidateInput),
                ).error
            assertEquals(path, error.fieldPath)
            assertEquals(Rg02ManualIncomeContractErrorReason.INVALID_ID, error.reason)
        }
    }

    @Test
    fun `missing or false explicit confirmation is rejected before application execution`() {
        listOf(
            Rg02JsonField.Omitted to Rg02ManualIncomeContractErrorReason.MISSING_REQUIRED_FIELD,
            Rg02JsonField.Null to Rg02ManualIncomeContractErrorReason.NULL_NOT_ALLOWED,
            Rg02JsonField.Value(false) to Rg02ManualIncomeContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED,
        ).forEach { (confirmation, reason) ->
            val error =
                assertIs<Rg02ManualIncomeAdaptResult.InvalidContract>(
                    adaptRg02ManualIncomeInput(context, input().copy(explicitConfirmation = confirmation)),
                ).error
            assertEquals("$.input.explicit_confirmation", error.fieldPath)
            assertEquals(reason, error.reason)
        }
    }

    @Test
    fun `sparse nullable business fields remain typed for application validation`() {
        val parsed =
            assertIs<Rg02ManualIncomeAdaptResult.Success>(
                adaptRg02ManualIncomeInput(
                    context,
                    input().copy(
                        amount = Rg02JsonField.Null,
                        categoryId = Rg02JsonField.Omitted,
                        receivingAccountId = Rg02JsonField.Null,
                    ),
                ),
            ).value

        assertEquals(null, parsed.saveInput.amount)
        assertEquals(null, parsed.saveInput.categoryId)
        assertEquals(null, parsed.saveInput.receivingAccountId)
    }

    @Test
    fun `category rename projects an explicit unsupported outcome`() {
        val rename = Rg02DecodedCategoryRename("income-category-salary", "New salary")
        assertEquals(
            Rg02CategoryRenameProjection.Unsupported(rename),
            projectRg02CategoryRename(rename),
        )
    }

    private fun input(
        amount: String = "3000.00",
        occurredAt: String = "2026-01-16T09:00:00+08:00",
    ) = Rg02DecodedManualIncomeInput(
        requestId = Rg02JsonField.Value("request-rg02-create"),
        occurredAt = Rg02JsonField.Value(occurredAt),
        amount = Rg02JsonField.Value(amount),
        currency = Rg02JsonField.Value("CNY"),
        categoryId = Rg02JsonField.Value("income-category-salary"),
        receivingAccountId = Rg02JsonField.Value("asset-bank-a"),
        note = Rg02JsonField.Value(""),
        explicitConfirmation = Rg02JsonField.Value(true),
    )
}
