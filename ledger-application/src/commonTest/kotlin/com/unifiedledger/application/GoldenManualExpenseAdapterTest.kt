package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GoldenManualExpenseAdapterTest {
    private val context =
        Rg01ManualExpenseContext(
            ledgerId = LedgerId("ledger-a"),
            currency = CurrencyUnit("CNY", 2),
        )

    private val sparseContext =
        Rg01ManualExpenseContext(
            ledgerId = LedgerId("ledger-a"),
            currency = CurrencyUnit("CNY", 2),
            catalog = sparseCatalog(),
        )

    @Test
    fun realSparseAttemptedShapeMapsFrozenRejectionsWithoutStrictDefaults() {
        val cases =
            listOf(
                Triple(sparseInput(amount = Rg01JsonField.Omitted), "$.attempted_input.amount", "missing_required_field"),
                Triple(sparseInput(amount = Rg01JsonField.Null), "$.attempted_input.amount", "missing_required_field"),
                Triple(sparseInput(paymentAccountId = Rg01JsonField.Omitted), "$.attempted_input.payment_account_id", "missing_required_field"),
                Triple(sparseInput(paymentAccountId = Rg01JsonField.Null), "$.attempted_input.payment_account_id", "missing_required_field"),
                Triple(sparseInput(categoryId = Rg01JsonField.Omitted), "$.attempted_input.category_id", "missing_required_field"),
                Triple(sparseInput(categoryId = Rg01JsonField.Null), "$.attempted_input.category_id", "missing_required_field"),
                Triple(sparseInput(amount = Rg01JsonField.Value("-0.01")), "$.attempted_input.amount", "must_be_positive"),
                Triple(sparseInput(amount = Rg01JsonField.Value("0.00")), "$.attempted_input.amount", "must_be_positive"),
                Triple(sparseInput(categoryId = Rg01JsonField.Value("expense-category-food")), "$.attempted_input.category_id", "secondary_category_required"),
                Triple(sparseInput(categoryId = Rg01JsonField.Value("expense-category-inactive")), "$.attempted_input.category_id", "category_inactive"),
            )

        cases.forEach { (decoded, expectedPath, expectedReason) ->
            val projection =
                assertIs<Rg01AttemptedExpenseResult.Mapped>(
                    evaluateRg01AttemptedManualExpense(sparseContext, decoded),
                ).projection
            assertEquals(Rg01OutcomeStatus.REJECTED, projection.status)
            assertEquals(expectedPath, projection.fieldPath)
            assertEquals(expectedReason, projection.reasonCode)
            assertEquals(emptySet(), projection.returnedIds)
        }
    }

    @Test
    fun sparseAttemptedIdsMustBeNonEmptyStableIds() {
        val cases =
            listOf(
                sparseInput(requestId = Rg01JsonField.Value("")) to "$.attempted_input.request_id",
                sparseInput(categoryId = Rg01JsonField.Value("")) to "$.attempted_input.category_id",
                sparseInput(paymentAccountId = Rg01JsonField.Value("")) to
                    "$.attempted_input.payment_account_id",
            )

        cases.forEach { (decoded, expectedPath) ->
            val error =
                assertIs<Rg01AttemptedExpenseResult.InvalidContract>(
                    evaluateRg01AttemptedManualExpense(sparseContext, decoded),
                ).error
            assertEquals(expectedPath, error.fieldPath)
            assertEquals(Rg01ContractErrorReason.INVALID_ID, error.reason)
        }
    }

    @Test
    fun sparseFalseConfirmationIsAContractErrorAndNegativeZeroIsRejected() {
        val confirmationError =
            assertIs<Rg01AttemptedExpenseResult.InvalidContract>(
                evaluateRg01AttemptedManualExpense(
                    sparseContext,
                    sparseInput(explicitConfirmation = Rg01JsonField.Value(false)),
                ),
            ).error
        assertEquals("$.attempted_input.explicit_confirmation", confirmationError.fieldPath)
        assertEquals(Rg01ContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED, confirmationError.reason)

        val negativeZero =
            assertIs<Rg01AttemptedExpenseResult.Mapped>(
                evaluateRg01AttemptedManualExpense(
                    sparseContext,
                    sparseInput(amount = Rg01JsonField.Value("-0.00")),
                ),
            ).projection
        assertEquals("$.attempted_input.amount", negativeZero.fieldPath)
        assertEquals("must_be_positive", negativeZero.reasonCode)
    }

    @Test
    fun strictInputParsesCanonicalAmountAndPreservesSourceText() {
        val parsed =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input()),
            ).value

        assertEquals(3_580L, parsed.saveInput.amount?.minorUnits)
        assertEquals(context.currency, parsed.saveInput.amount?.currency)
        assertEquals("35.80", parsed.originalAmountText)
        assertEquals("2026-01-15T08:30:00+08:00", parsed.originalOccurredAtText)
        assertEquals(Instant.parse("2026-01-15T00:30:00Z"), parsed.saveInput.occurredAt)
        assertEquals(ExplicitManualSave, parsed.saveInput.confirmation)
    }

    @Test
    fun presenceWrapperDistinguishesOmittedAndExplicitNull() {
        assertNotNull(Rg01JsonField.Omitted)
        assertNotNull(Rg01JsonField.Null)
        assertTrue(Rg01JsonField.Omitted != Rg01JsonField.Null)
        assertEquals(
            Rg01JsonField.Value("35.80"),
            Rg01JsonField.Value("35.80"),
        )
    }

    @Test
    fun strictFieldsDoNotReceiveDefaultsWhenOmittedOrNull() {
        val cases =
            listOf(
                input().copy(requestId = Rg01JsonField.Omitted) to "$.input.request_id",
                input().copy(requestId = Rg01JsonField.Null) to "$.input.request_id",
                input().copy(amount = Rg01JsonField.Omitted) to "$.input.amount",
                input().copy(amount = Rg01JsonField.Null) to "$.input.amount",
                input().copy(currency = Rg01JsonField.Omitted) to "$.input.currency",
                input().copy(currency = Rg01JsonField.Null) to "$.input.currency",
                input().copy(categoryId = Rg01JsonField.Omitted) to "$.input.category_id",
                input().copy(categoryId = Rg01JsonField.Null) to "$.input.category_id",
                input().copy(paymentAccountId = Rg01JsonField.Omitted) to
                    "$.input.payment_account_id",
                input().copy(paymentAccountId = Rg01JsonField.Null) to
                    "$.input.payment_account_id",
                input().copy(occurredAt = Rg01JsonField.Omitted) to "$.input.occurred_at",
                input().copy(occurredAt = Rg01JsonField.Null) to "$.input.occurred_at",
                input().copy(note = Rg01JsonField.Omitted) to "$.input.note",
                input().copy(note = Rg01JsonField.Null) to "$.input.note",
                input().copy(explicitConfirmation = Rg01JsonField.Omitted) to
                    "$.input.explicit_confirmation",
                input().copy(explicitConfirmation = Rg01JsonField.Null) to
                    "$.input.explicit_confirmation",
            )

        cases.forEach { (decoded, expectedPath) ->
            val error =
                assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                    parseRg01ManualExpenseInput(context, decoded),
                ).error
            assertEquals(expectedPath, error.fieldPath)
            assertTrue(
                error.reason == Rg01ContractErrorReason.MISSING_REQUIRED_FIELD ||
                    error.reason == Rg01ContractErrorReason.NULL_NOT_ALLOWED,
            )
        }
    }

    @Test
    fun strictInputIdsMustBeNonEmptyStableIds() {
        val cases =
            listOf(
                input().copy(requestId = Rg01JsonField.Value("")) to "$.input.request_id",
                input().copy(categoryId = Rg01JsonField.Value("")) to "$.input.category_id",
                input().copy(paymentAccountId = Rg01JsonField.Value("")) to
                    "$.input.payment_account_id",
            )

        cases.forEach { (decoded, expectedPath) ->
            val error =
                assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                    parseRg01ManualExpenseInput(context, decoded),
                ).error
            assertEquals(expectedPath, error.fieldPath)
            assertEquals(Rg01ContractErrorReason.INVALID_ID, error.reason)
        }
    }

    @Test
    fun zeroAndNegativeAmountsParseAsPresentValuesForDomainValidation() {
        val zero =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input(amount = "0.00")),
            ).value
        val negative =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input(amount = "-0.01")),
            ).value

        assertEquals(0L, zero.saveInput.amount?.minorUnits)
        assertEquals(-1L, negative.saveInput.amount?.minorUnits)
        assertEquals(
            Rg01OutcomeProjection(
                status = Rg01OutcomeStatus.REJECTED,
                fieldPath = "$.attempted_input.amount",
                reasonCode = "must_be_positive",
                returnedIds = emptySet(),
            ),
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.Executed(
                    ConfirmedManualExpenseResult.Rejected(
                        OrdinaryExpenseViolation.AmountMustBePositive,
                    ),
                ),
            ).mappedOrFail(),
        )
    }

    @Test
    fun malformedDecimalIsAContractError() {
        listOf("35.8", "035.80", "+35.80", "3.58e1", ".50", "1.", "-0.00")
            .forEach { malformed ->
                val error =
                    assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                        parseRg01ManualExpenseInput(context, input(amount = malformed)),
                    ).error
                assertEquals("$.input.amount", error.fieldPath)
                assertEquals(Rg01ContractErrorReason.INVALID_DECIMAL, error.reason)
            }
    }

    @Test
    fun malformedTimestampIsAContractError() {
        listOf(
            "2026-01-15T08:30+08:00",
            "2026-01-15T08:30:00",
            "2026-01-15T08:30:00-00:00",
            "2026-01-15T08:30:60+08:00",
            "2026-01-15T08:30:00+14:01",
            "2026-01-15T08:30:00-15:00",
            "2026-01-15T08:30:00+23:00",
        ).forEach { malformed ->
            val error =
                assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                    parseRg01ManualExpenseInput(context, input(occurredAt = malformed)),
                ).error
            assertEquals("$.input.occurred_at", error.fieldPath)
            assertEquals(Rg01ContractErrorReason.INVALID_TIMESTAMP, error.reason)
        }
    }

    @Test
    fun timestampMustMatchTheSupportedCaseTimezoneOffsetOrUtc() {
        val local =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input()),
            ).value
        val utc =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input(occurredAt = "2026-01-15T00:30:00Z")),
            ).value
        assertEquals(local.saveInput.occurredAt, utc.saveInput.occurredAt)

        listOf("2026-01-15T07:30:00+07:00", "2026-01-15T14:30:00+14:00")
            .forEach { timestamp ->
                val strictError =
                    assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                        parseRg01ManualExpenseInput(context, input(occurredAt = timestamp)),
                    ).error
                assertEquals(Rg01ContractErrorReason.TIMEZONE_OFFSET_MISMATCH, strictError.reason)

                val sparseError =
                    assertIs<Rg01AttemptedExpenseResult.InvalidContract>(
                        evaluateRg01AttemptedManualExpense(
                            sparseContext,
                            sparseInput(occurredAt = Rg01JsonField.Value(timestamp)),
                        ),
                    ).error
                assertEquals(Rg01ContractErrorReason.TIMEZONE_OFFSET_MISMATCH, sparseError.reason)
            }

        val unsupported =
            assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                parseRg01ManualExpenseInput(
                    context.copy(caseTimeZone = "Etc/UTC", validNumericOffset = "+00:00"),
                    input(occurredAt = "2026-01-15T00:30:00Z"),
                ),
            ).error
        assertEquals(Rg01ContractErrorReason.UNSUPPORTED_TIMEZONE, unsupported.reason)
    }

    @Test
    fun exactDecimalParserSupportsTheFullLongRangeWithoutOverflow() {
        val maximum =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input(amount = "92233720368547758.07")),
            ).value
        val minimum =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input(amount = "-92233720368547758.08")),
            ).value
        assertEquals(Long.MAX_VALUE, maximum.saveInput.amount?.minorUnits)
        assertEquals(Long.MIN_VALUE, minimum.saveInput.amount?.minorUnits)

        val attemptedMinimum =
            assertIs<Rg01AttemptedExpenseResult.Mapped>(
                evaluateRg01AttemptedManualExpense(
                    sparseContext,
                    sparseInput(amount = Rg01JsonField.Value("-92233720368547758.08")),
                ),
            ).projection
        assertEquals("must_be_positive", attemptedMinimum.reasonCode)

        listOf("92233720368547758.08", "-92233720368547758.09").forEach { overflow ->
            assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                parseRg01ManualExpenseInput(context, input(amount = overflow)),
            )
            assertIs<Rg01AttemptedExpenseResult.InvalidContract>(
                evaluateRg01AttemptedManualExpense(
                    sparseContext,
                    sparseInput(amount = Rg01JsonField.Value(overflow)),
                ),
            )
        }
    }

    @Test
    fun falseConfirmationAndCurrencyMismatchAreContractErrors() {
        val falseConfirmation =
            assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                parseRg01ManualExpenseInput(context, input(explicitConfirmation = false)),
            ).error
        assertEquals("$.input.explicit_confirmation", falseConfirmation.fieldPath)
        assertEquals(
            Rg01ContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED,
            falseConfirmation.reason,
        )

        val currencyMismatch =
            assertIs<Rg01ManualExpenseParseResult.InvalidContract>(
                parseRg01ManualExpenseInput(context, input(currency = "USD")),
            ).error
        assertEquals("$.input.currency", currencyMismatch.fieldPath)
        assertEquals(Rg01ContractErrorReason.CURRENCY_MISMATCH, currencyMismatch.reason)
    }

    @Test
    fun missingFieldsUseFrozenPrecedenceAndReturnNoIds() {
        val missingAmount =
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.InvalidInput(
                    setOf(ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT)),
                ),
            ).mappedOrFail()
        val missingPayment =
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.InvalidInput(
                    setOf(ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT)),
                ),
            ).mappedOrFail()
        val missingCategory =
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.InvalidInput(
                    setOf(ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY)),
                ),
            ).mappedOrFail()
        val allMissing =
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.InvalidInput(
                    setOf(
                        ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT),
                        ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT),
                        ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY),
                    ),
                ),
            ).mappedOrFail()

        assertEquals("$.attempted_input.amount", missingAmount.fieldPath)
        assertEquals("$.attempted_input.payment_account_id", missingPayment.fieldPath)
        assertEquals("$.attempted_input.category_id", missingCategory.fieldPath)
        assertEquals("$.attempted_input.amount", allMissing.fieldPath)
        listOf(missingAmount, missingPayment, missingCategory, allMissing).forEach {
            assertEquals(Rg01OutcomeStatus.REJECTED, it.status)
            assertEquals("missing_required_field", it.reasonCode)
            assertEquals(emptySet(), it.returnedIds)
        }
    }

    @Test
    fun primaryAndInactiveCategoriesMapToFrozenDomainReasons() {
        val primary =
            projectRg01ManualExpenseResult(
                rejected(OrdinaryExpenseViolation.SecondaryCategoryRequired),
            ).mappedOrFail()
        val inactive =
            projectRg01ManualExpenseResult(
                rejected(OrdinaryExpenseViolation.CategoryInactive),
            ).mappedOrFail()

        assertEquals("$.attempted_input.category_id", primary.fieldPath)
        assertEquals("secondary_category_required", primary.reasonCode)
        assertEquals("$.attempted_input.category_id", inactive.fieldPath)
        assertEquals("category_inactive", inactive.reasonCode)
    }

    @Test
    fun createdAndNoChangeProjectReceiptIds() {
        val created =
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.Executed(
                    ConfirmedManualExpenseResult.Created(
                        ConfirmedExpenseReceipt(
                            confirmationId = ConfirmationId("confirmation-rg01-1"),
                            transactionId = TransactionId("tx-expense-rg01"),
                        ),
                    ),
                ),
            ).mappedOrFail()
        val replay =
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.Executed(
                    ConfirmedManualExpenseResult.NoChange(
                        ConfirmedExpenseReceipt(
                            confirmationId = ConfirmationId("confirmation-rg01-1"),
                            transactionId = TransactionId("tx-expense-rg01"),
                        ),
                    ),
                ),
            ).mappedOrFail()

        assertEquals(Rg01OutcomeStatus.ACCEPTED, created.status)
        assertEquals(
            setOf(
                Rg01ReturnedId("confirmation", "confirmation-rg01-1"),
                Rg01ReturnedId("transaction", "tx-expense-rg01"),
            ),
            created.returnedIds,
        )
        assertEquals(Rg01OutcomeStatus.NO_CHANGE, replay.status)
        assertEquals("idempotent_replay", replay.reasonCode)
        assertEquals(created.returnedIds, replay.returnedIds)
    }

    @Test
    fun identityConflictAndGenericViolationsAreExplicitlyUnsupported() {
        val conflict =
            projectRg01ManualExpenseResult(
                ManualExpenseSaveResult.Executed(
                    ConfirmedManualExpenseResult.RequestIdentityConflict(
                        ManualExpenseRequestIdentity(
                            ledgerId = context.ledgerId,
                            requestId = RequestId("request-rg01-create"),
                        ),
                    ),
                ),
            )
        val generic =
            projectRg01ManualExpenseResult(
                rejected(DomainViolation.InvalidOrdinaryExpense),
            )

        assertIs<Rg01ProjectionResult.Unsupported>(conflict)
        assertIs<Rg01ProjectionResult.Unsupported>(generic)
    }

    @Test
    fun existingUseCaseCreateAndReplayProjectToAcceptedAndNoChange() {
        val harness = Rg01AdapterHarness()
        val parsed =
            assertIs<Rg01ManualExpenseParseResult.Success>(
                parseRg01ManualExpenseInput(context, input()),
            ).value

        val created =
            projectRg01ManualExpenseResult(
                harness.execute(parsed.saveInput),
            ).mappedOrFail()
        val replay =
            projectRg01ManualExpenseResult(
                harness.execute(parsed.saveInput),
            ).mappedOrFail()

        assertEquals(Rg01OutcomeStatus.ACCEPTED, created.status)
        assertEquals(Rg01OutcomeStatus.NO_CHANGE, replay.status)
        assertEquals("idempotent_replay", replay.reasonCode)
        assertEquals(created.returnedIds, replay.returnedIds)
        assertEquals(1, harness.callbackCount)
    }

    private fun input(
        amount: String = "35.80",
        currency: String = "CNY",
        occurredAt: String = "2026-01-15T08:30:00+08:00",
        explicitConfirmation: Boolean = true,
    ) = Rg01DecodedManualExpenseInput(
        requestId = Rg01JsonField.Value("request-rg01-create"),
        amount = Rg01JsonField.Value(amount),
        currency = Rg01JsonField.Value(currency),
        categoryId = Rg01JsonField.Value("expense-category-breakfast"),
        paymentAccountId = Rg01JsonField.Value("asset-bank-a"),
        occurredAt = Rg01JsonField.Value(occurredAt),
        note = Rg01JsonField.Value(""),
        explicitConfirmation = Rg01JsonField.Value(explicitConfirmation),
    )

    private fun sparseInput(
        requestId: Rg01JsonField<String> = Rg01JsonField.Value("request-rg01-rejected"),
        amount: Rg01JsonField<String> = Rg01JsonField.Value("35.80"),
        categoryId: Rg01JsonField<String> =
            Rg01JsonField.Value("expense-category-breakfast"),
        paymentAccountId: Rg01JsonField<String> = Rg01JsonField.Value("asset-bank-a"),
        occurredAt: Rg01JsonField<String> = Rg01JsonField.Omitted,
        explicitConfirmation: Rg01JsonField<Boolean> = Rg01JsonField.Omitted,
    ) = Rg01DecodedManualExpenseInput(
        requestId = requestId,
        amount = amount,
        currency = Rg01JsonField.Omitted,
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        occurredAt = occurredAt,
        note = Rg01JsonField.Omitted,
        explicitConfirmation = explicitConfirmation,
    )

    private fun sparseCatalog(): LedgerCatalog =
        LedgerCatalog
            .create(
                accounts =
                    listOf(
                        Account(
                            id = AccountId("asset-bank-a"),
                            ledgerId = LedgerId("ledger-a"),
                            kind = AccountKind.ASSET,
                            currency = CurrencyUnit("CNY", 2),
                            ownedByUser = true,
                            realAccount = true,
                        ),
                        Account(
                            id = AccountId("expense-account-breakfast"),
                            ledgerId = LedgerId("ledger-a"),
                            kind = AccountKind.EXPENSE,
                            currency = CurrencyUnit("CNY", 2),
                            ownedByUser = false,
                            realAccount = false,
                        ),
                    ),
                categories =
                    listOf(
                        Category(
                            id = CategoryId("expense-category-food"),
                            ledgerId = LedgerId("ledger-a"),
                            parentId = null,
                            postingAccountId = null,
                            active = true,
                        ),
                        Category(
                            id = CategoryId("expense-category-breakfast"),
                            ledgerId = LedgerId("ledger-a"),
                            parentId = CategoryId("expense-category-food"),
                            postingAccountId = AccountId("expense-account-breakfast"),
                            active = true,
                        ),
                        Category(
                            id = CategoryId("expense-category-inactive"),
                            ledgerId = LedgerId("ledger-a"),
                            parentId = CategoryId("expense-category-food"),
                            postingAccountId = AccountId("expense-account-breakfast"),
                            active = false,
                        ),
                    ),
            ).successValue()

    private fun rejected(violation: DomainViolation) = ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.Rejected(violation))

    private class Rg01AdapterHarness {
        private val catalog =
            LedgerCatalog
                .create(
                    accounts =
                        listOf(
                            Account(
                                id = AccountId("asset-bank-a"),
                                ledgerId = LedgerId("ledger-a"),
                                kind = AccountKind.ASSET,
                                currency = CurrencyUnit("CNY", 2),
                                ownedByUser = true,
                                realAccount = true,
                            ),
                            Account(
                                id = AccountId("expense-account-breakfast"),
                                ledgerId = LedgerId("ledger-a"),
                                kind = AccountKind.EXPENSE,
                                currency = CurrencyUnit("CNY", 2),
                                ownedByUser = false,
                                realAccount = false,
                            ),
                        ),
                    categories =
                        listOf(
                            Category(
                                id = CategoryId("expense-category-food"),
                                ledgerId = LedgerId("ledger-a"),
                                parentId = null,
                                postingAccountId = null,
                                active = true,
                            ),
                            Category(
                                id = CategoryId("expense-category-breakfast"),
                                ledgerId = LedgerId("ledger-a"),
                                parentId = CategoryId("expense-category-food"),
                                postingAccountId = AccountId("expense-account-breakfast"),
                                active = true,
                            ),
                        ),
                ).successValue()

        private val receipt =
            ConfirmedExpenseReceipt(
                confirmationId = ConfirmationId("confirmation-rg01-1"),
                transactionId = TransactionId("tx-expense-rg01"),
            )
        private var storedSnapshot: ManualExpenseRequestSnapshot? = null
        var callbackCount = 0
            private set

        private val useCase =
            ExecuteManualExpenseSave(
                executeConfirmed =
                    ExecuteConfirmedManualExpense(
                        commitPort =
                            ConfirmedManualExpenseCommitPort { _, snapshot, callback ->
                                val existing = storedSnapshot
                                if (existing != null) {
                                    return@ConfirmedManualExpenseCommitPort if (existing == snapshot) {
                                        ConfirmedManualExpenseResult.NoChange(receipt)
                                    } else {
                                        ConfirmedManualExpenseResult.RequestIdentityConflict(
                                            ManualExpenseRequestIdentity(snapshot.ledgerId, RequestId("request-rg01-create")),
                                        )
                                    }
                                }
                                callbackCount += 1
                                val created = callback()
                                if (created is DomainResult.Failure) {
                                    ConfirmedManualExpenseResult.Rejected(created.violation)
                                } else {
                                    storedSnapshot = snapshot
                                    ConfirmedManualExpenseResult.Created(receipt)
                                }
                            },
                        idSource =
                            ConfirmedManualExpenseIdSource {
                                ConfirmedManualExpenseCommitIds(
                                    confirmationId = receipt.confirmationId,
                                    expenseIds =
                                        AssetPaidOrdinaryExpenseIds(
                                            transactionId = receipt.transactionId,
                                            versionId = TransactionVersionId("version-expense-rg01-v1"),
                                            postingSetId = PostingSetId("posting-set-expense-rg01"),
                                            expensePostingId = PostingId("posting-expense-rg01"),
                                            paymentPostingId = PostingId("posting-bank-rg01"),
                                        ),
                                )
                            },
                        createFormalTransaction =
                            ConfirmedExpenseTransactionFactory { request, ids ->
                                when (
                                    val result =
                                        createAssetPaidOrdinaryExpense(
                                            catalog = catalog,
                                            command =
                                                AssetPaidOrdinaryExpenseCommand(
                                                    ledgerId = request.ledgerId,
                                                    amount = request.amount,
                                                    categoryId = request.categoryId,
                                                    paymentAccountId = request.paymentAccountId,
                                                    times = TransactionTimes.collapsed(request.occurredAt),
                                                ),
                                            ids = ids.expenseIds,
                                        )
                                ) {
                                    is DomainResult.Failure -> result
                                    is DomainResult.Success ->
                                        DomainResult.Success(
                                            ConfirmedManualExpenseCommit(ids.confirmationId, result.value),
                                        )
                                }
                            },
                    ),
            )

        fun execute(input: ManualExpenseSaveInput): ManualExpenseSaveResult = useCase.execute(input)
    }
}

private fun <T> DomainResult<T>.successValue(): T = (this as DomainResult.Success).value

private fun Rg01ProjectionResult.mappedOrFail(): Rg01OutcomeProjection = (this as Rg01ProjectionResult.Mapped).projection
