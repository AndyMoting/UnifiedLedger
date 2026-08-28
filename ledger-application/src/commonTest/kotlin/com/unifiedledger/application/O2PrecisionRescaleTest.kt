package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CreditRefundOriginalExpense
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class O2PrecisionRescaleTest {
    @Test
    fun exactNormalizerUsesOnlyIntegerScaleOperations() {
        assertEquals(ExactAmountNormalization.Success(9900L), normalizeSourceMinorExact(99L, 0, 2))
        assertEquals(ExactAmountNormalization.Success(50L), normalizeSourceMinorExact(5L, 1, 2))
        assertEquals(ExactAmountNormalization.Success(123L), normalizeSourceMinorExact(12300L, 4, 2))
        assertEquals(ExactAmountNormalization.Success(-50L), normalizeSourceMinorExact(-5L, 1, 2))
        assertEquals(ExactAmountNormalization.Success(0L), normalizeSourceMinorExact(0L, Int.MAX_VALUE, 2))
        assertEquals(ExactAmountNormalization.NotRepresentable, normalizeSourceMinorExact(1234L, 3, 2))
        assertEquals(ExactAmountNormalization.NotRepresentable, normalizeSourceMinorExact(1L, Int.MAX_VALUE, 2))
        assertEquals(ExactAmountNormalization.NotRepresentable, normalizeSourceMinorExact(1L, -1, 2))
        assertEquals(ExactAmountNormalization.ArithmeticOverflow, normalizeSourceMinorExact(Long.MAX_VALUE, 0, 2))
    }

    @Test
    fun allApprovedFactoriesUseTheExplicitAccountCurrency() {
        val catalog = fixtureCatalog()
        val ordinary =
            formal(
                OrdinaryFlowFormalFactory(catalog),
                source(amountMinor = 99L, precision = 0, direction = "out"),
                ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), AccountId("asset")),
                ids("ordinary", 2),
            )
        assertEquals(listOf(9900L, -9900L), ordinary.map { it.amount.minorUnits })
        assertEquals(CurrencyUnit("CNY", 2), ordinary.first().amount.currency)

        val credit =
            formal(
                CreditFlowFormalFactory(catalog) { originalExpense(it) },
                source(amountMinor = 5L, precision = 1, direction = "out"),
                ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("liability")),
                ids("credit", 2),
            )
        assertEquals(listOf(50L, -50L), credit.map { it.amount.minorUnits })

        val refund =
            formal(
                CreditFlowFormalFactory(catalog) { originalExpense(it) },
                source(amountMinor = 5L, precision = 1, direction = "out"),
                ImportConfirmDecisionFields.CreditExpenseRefundFlow(
                    CategoryId("category-food"),
                    AccountId("liability"),
                    TransactionId("original"),
                ),
                ids("refund", 2),
            )
        assertEquals(listOf(50L, -50L), refund.map { it.amount.minorUnits })

        val transfer =
            formal(
                TransferFlowFormalFactory(catalog, AccountId("asset")),
                source(amountMinor = 99L, precision = 0, direction = "out"),
                ImportConfirmDecisionFields.TransferFlow(AccountId("asset"), AccountId("asset-2")),
                ids("transfer", 2),
            )
        assertEquals(listOf(-9900L, 9900L), transfer.map { it.amount.minorUnits })

        val income =
            formal(
                OrdinaryFlowFormalFactory(catalog),
                source(amountMinor = 99L, precision = 0, direction = "in"),
                ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-income"), AccountId("asset")),
                ids("income", 2),
            )
        assertEquals(listOf(9900L, -9900L), income.map { it.amount.minorUnits })

        val repayment =
            formal(
                CreditFlowFormalFactory(catalog) { originalExpense(it) },
                source(amountMinor = 99L, precision = 0, direction = "out"),
                ImportConfirmDecisionFields.CreditRepaymentFlow(AccountId("asset"), AccountId("liability")),
                ids("repayment", 2),
            )
        assertEquals(listOf(-9900L, 9900L), repayment.map { it.amount.minorUnits })

        val mixed =
            formal(
                MixedPaymentFlowFormalFactory(catalog),
                source(amountMinor = 99L, precision = 0, direction = "out"),
                ImportConfirmDecisionFields.MixedPaymentFlow(
                    CategoryId("category-food"),
                    AccountId("asset"),
                    AccountId("liability"),
                    5000L,
                    4900L,
                ),
                ids("mixed", 3),
            )
        assertEquals(listOf(9900L, -5000L, -4900L), mixed.map { it.amount.minorUnits })
    }

    @Test
    fun mixedLegValidationRejectsInvalidTotalsAndArithmeticOverflow() {
        val catalog = fixtureCatalog()

        fun create(
            assetLeg: Long,
            creditLeg: Long,
        ): DomainResult<ImportFormalCommit> =
            MixedPaymentFlowFormalFactory(catalog).create(
                ImportCandidateFormalizationInput(
                    LedgerId("ledger"),
                    source(amountMinor = 99L, precision = 0),
                    ImportConfirmDecisionFields.MixedPaymentFlow(
                        CategoryId("category-food"),
                        AccountId("asset"),
                        AccountId("liability"),
                        assetLeg,
                        creditLeg,
                    ),
                ),
                ids("mixed-invalid-$assetLeg-$creditLeg", 3),
            )

        assertIs<com.unifiedledger.domain.MixedPaymentViolation.FundingLegMustBePositive>(
            assertIs<DomainResult.Failure>(create(0L, 9900L)).violation,
        )
        assertIs<com.unifiedledger.domain.MixedPaymentViolation.FundingLegMustBePositive>(
            assertIs<DomainResult.Failure>(create(-1L, 9901L)).violation,
        )
        assertIs<com.unifiedledger.domain.MixedPaymentViolation.FundingTotalMustEqualExpense>(
            assertIs<DomainResult.Failure>(create(5000L, 4800L)).violation,
        )
        assertIs<DomainViolation.ArithmeticOverflow>(
            assertIs<DomainResult.Failure>(create(Long.MAX_VALUE, 1L)).violation,
        )
    }

    @Test
    fun nonRepresentableAndCurrencyMismatchFailBeforeDomainAssembly() {
        val catalog = fixtureCatalog()
        val nonRepresentable =
            OrdinaryFlowFormalFactory(catalog).create(
                ImportCandidateFormalizationInput(
                    LedgerId("ledger"),
                    source(amountMinor = 1234L, precision = 3),
                    ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), AccountId("asset")),
                ),
                ids("reject", 2),
            )
        val nonRepresentableFailure = assertIs<DomainResult.Failure>(nonRepresentable)
        assertIs<DomainViolation.AmountNotRepresentableInCurrency>(nonRepresentableFailure.violation)

        val mismatch =
            OrdinaryFlowFormalFactory(catalog).create(
                ImportCandidateFormalizationInput(
                    LedgerId("ledger"),
                    source(currencyCode = "USD", amountMinor = 99L, precision = 0),
                    ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), AccountId("asset")),
                ),
                ids("mismatch", 2),
            )
        assertIs<DomainViolation.AmountNotRepresentableInCurrency>(assertIs<DomainResult.Failure>(mismatch).violation)
    }

    @Test
    fun malformedOccurredAtIsReturnedAsTypedFactoryFailure() {
        val catalog = fixtureCatalog()
        val malformed = source(amountMinor = 99L, precision = 0).copy(occurredAt = "not-an-instant")

        fun assertMalformed(
            factory: ImportCandidateFormalFactory,
            fields: ImportConfirmDecisionFields,
            prefix: String,
        ) {
            val result =
                factory.create(
                    ImportCandidateFormalizationInput(LedgerId("ledger"), malformed, fields),
                    ids(prefix, if (fields is ImportConfirmDecisionFields.MixedPaymentFlow) 3 else 2),
                )
            assertEquals(
                DomainViolation.InvalidFormalTransaction,
                assertIs<DomainResult.Failure>(result).violation,
            )
        }

        assertMalformed(
            OrdinaryFlowFormalFactory(catalog),
            ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), AccountId("asset")),
            "malformed-ordinary",
        )
        assertMalformed(
            TransferFlowFormalFactory(catalog, AccountId("asset")),
            ImportConfirmDecisionFields.TransferFlow(AccountId("asset"), AccountId("asset-2")),
            "malformed-transfer",
        )
        assertMalformed(
            CreditFlowFormalFactory(catalog) { null },
            ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("liability")),
            "malformed-credit",
        )
        assertMalformed(
            MixedPaymentFlowFormalFactory(catalog),
            ImportConfirmDecisionFields.MixedPaymentFlow(
                CategoryId("category-food"),
                AccountId("asset"),
                AccountId("liability"),
                5000L,
                4900L,
            ),
            "malformed-mixed",
        )
    }

    @Test
    fun genericBindingRejectsAFormallyValidGraphWithAReplacedPostingId() {
        val catalog = fixtureCatalog()
        val input =
            ImportCandidateFormalizationInput(
                LedgerId("ledger"),
                source(amountMinor = 99L, precision = 0),
                ImportConfirmDecisionFields.TransferFlow(AccountId("asset"), AccountId("asset-2")),
            )
        val ids = ids("generic-binding", 2)
        val canonical =
            assertIs<DomainResult.Success<ImportFormalCommit>>(
                TransferFlowFormalFactory(catalog, AccountId("asset")).create(input, ids),
            ).value
        val malicious =
            rebuildTransferGraph(
                base = canonical,
                currency = CurrencyUnit("CNY", 2),
                amountMinor = 9900L,
                sourcePostingId = PostingId("tampered-source-posting"),
            )

        val failure = assertIs<DomainResult.Failure>(validateImportFormalBinding(input, ids, malicious))
        assertEquals(DomainViolation.InvalidFormalTransaction, failure.violation)
    }

    @Test
    fun catalogBindingRejectsAValidGraphUsingTheWrongAccountPrecision() {
        val catalog = fixtureCatalog()
        val input =
            ImportCandidateFormalizationInput(
                LedgerId("ledger"),
                source(amountMinor = 99L, precision = 0),
                ImportConfirmDecisionFields.TransferFlow(AccountId("asset"), AccountId("asset-2")),
            )
        val ids = ids("catalog-binding", 2)
        val canonical =
            assertIs<DomainResult.Success<ImportFormalCommit>>(
                TransferFlowFormalFactory(catalog, AccountId("asset")).create(input, ids),
            ).value
        // The graph is internally balanced and generic binding-valid at precision 3,
        // but both selected catalog accounts are explicitly CNY precision 2.
        val wrongPrecisionGraph =
            rebuildTransferGraph(
                base = canonical,
                currency = CurrencyUnit("CNY", 3),
                amountMinor = 99_000L,
            )
        assertIs<DomainResult.Success<Unit>>(validateImportFormalBinding(input, ids, wrongPrecisionGraph))

        val failure =
            assertIs<DomainResult.Failure>(
                validateImportFormalBindingAgainstCatalog(input, ids, wrongPrecisionGraph, catalog),
            )
        assertEquals(DomainViolation.InvalidFormalTransaction, failure.violation)
    }

    private fun formal(
        factory: ImportCandidateFormalFactory,
        resolved: ImportResolvedSourceFacts,
        fields: ImportConfirmDecisionFields,
        ids: ImportCommitIds,
    ): List<Posting> {
        val input = ImportCandidateFormalizationInput(LedgerId("ledger"), resolved, fields)
        val result = assertIs<DomainResult.Success<ImportFormalCommit>>(factory.create(input, ids)).value
        return result.transaction.postingSets
            .single()
            .postings
    }

    private fun rebuildTransferGraph(
        base: ImportFormalCommit,
        currency: CurrencyUnit,
        amountMinor: Long,
        sourcePostingId: PostingId =
            base.transaction.postingSets
                .single()
                .postings[0]
                .id,
        destinationPostingId: PostingId =
            base.transaction.postingSets
                .single()
                .postings[1]
                .id,
    ): ImportFormalCommit {
        val originalSet = base.transaction.postingSets.single()
        val postingSet =
            assertIs<DomainResult.Success<PostingSet>>(
                PostingSet.create(
                    originalSet.id,
                    listOf(
                        originalSet.postings[0].copy(
                            id = sourcePostingId,
                            amount = Money.ofMinor(-amountMinor, currency),
                        ),
                        originalSet.postings[1].copy(
                            id = destinationPostingId,
                            amount = Money.ofMinor(amountMinor, currency),
                        ),
                    ),
                ),
            ).value
        val formal =
            assertIs<DomainResult.Success<FormalTransaction>>(
                FormalTransaction.create(base.transaction.transaction, base.transaction.versions, listOf(postingSet)),
            ).value
        return ImportFormalCommit(base.confirmationId, base.statusHistoryId, formal)
    }

    private fun source(
        amountMinor: Long,
        precision: Int,
        currencyCode: String = "CNY",
        direction: String = "out",
    ) = ImportResolvedSourceFacts(
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        currencyPrecision = precision,
        occurredAt = "2026-08-23T10:00:00+08:00",
        directionToken = direction,
        statusToken = "SUCCESS",
    )

    private fun ids(
        prefix: String,
        postingCount: Int,
    ) = ImportCommitIds(
        confirmationId = ImportConfirmationId("confirmation-$prefix"),
        statusHistoryId = ImportStatusHistoryId("status-$prefix"),
        formalIds =
            ImportFormalIds(
                transactionId = TransactionId("transaction-$prefix"),
                versionId = com.unifiedledger.domain.TransactionVersionId("version-$prefix"),
                postingSetId = com.unifiedledger.domain.PostingSetId("set-$prefix"),
                postingIds = (0 until postingCount).map { com.unifiedledger.domain.PostingId("posting-$prefix-$it") },
            ),
    )

    private fun originalExpense(id: TransactionId) =
        CreditRefundOriginalExpense(
            transactionId = id,
            ledgerId = LedgerId("ledger"),
            kind = TransactionKind.EXPENSE,
            currencyCode = "CNY",
            currentExpensePostingAccountId = AccountId("expense"),
        )

    private fun fixtureCatalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(AccountId("asset"), LedgerId("ledger"), AccountKind.ASSET, CurrencyUnit("CNY", 2), true, true),
                        Account(AccountId("asset-2"), LedgerId("ledger"), AccountKind.ASSET, CurrencyUnit("CNY", 2), true, true),
                        Account(AccountId("liability"), LedgerId("ledger"), AccountKind.LIABILITY, CurrencyUnit("CNY", 2), true, true),
                        Account(AccountId("expense"), LedgerId("ledger"), AccountKind.EXPENSE, CurrencyUnit("CNY", 2), false, false),
                        Account(AccountId("income"), LedgerId("ledger"), AccountKind.INCOME, CurrencyUnit("CNY", 2), false, false),
                    ),
                categories =
                    listOf(
                        Category(CategoryId("parent-expense"), LedgerId("ledger"), null, null, true, CategoryKind.EXPENSE),
                        Category(CategoryId("category-food"), LedgerId("ledger"), CategoryId("parent-expense"), AccountId("expense"), true, CategoryKind.EXPENSE),
                        Category(CategoryId("parent-income"), LedgerId("ledger"), null, null, true, CategoryKind.INCOME),
                        Category(CategoryId("category-income"), LedgerId("ledger"), CategoryId("parent-income"), AccountId("income"), true, CategoryKind.INCOME),
                    ),
            ),
        ).value
}
