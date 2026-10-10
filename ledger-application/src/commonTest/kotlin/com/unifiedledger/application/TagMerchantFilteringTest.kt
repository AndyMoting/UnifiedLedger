package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-08 08.C evidence (P708-A01 intent labels; no P708 vector is recorded PASS here — the
 * ledger-data read vectors and the scale leg carry their own files): the pure filter surface.
 *
 * A01 semantics pinned as pure functions: the condition fingerprint is canonical (tag order
 * irrelevant, different conditions differ); OR/AND matching matches the spec section 5.1
 * statement; per-tag groups show each tag's FULL overlap total and are never additive; the
 * merchant accumulation reuses the frozen ordinary classifier (a refund contributes only
 * through its own explicit association) — the D-184 behavior-equivalence hard constraint.
 */
class TagMerchantFilteringTest {
    private val ledgerId = LedgerId("ledger-filter")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetId = AccountId("account-asset")
    private val expenseId = AccountId("account-expense")
    private val tagA = TagId("tag-a")
    private val tagB = TagId("tag-b")

    // ---- condition fingerprint ----

    @Test
    fun fingerprintIsCanonicalAndDistinguishesConditions() {
        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA, tagB), merchantId = MerchantId("merchant-1"))
        // Set iteration order must not change the fingerprint (stable-id canonical order).
        assertEquals(
            tagMerchantFilterConditionFingerprint(ledgerId, condition),
            tagMerchantFilterConditionFingerprint(ledgerId, condition.copy(tagIds = setOf(tagB, tagA))),
        )
        // Different conditions (and different ledgers) never share a fingerprint.
        assertNotEquals(
            tagMerchantFilterConditionFingerprint(ledgerId, condition),
            tagMerchantFilterConditionFingerprint(ledgerId, condition.copy(tagMatch = TagMerchantTagMatchMode.All)),
        )
        assertNotEquals(
            tagMerchantFilterConditionFingerprint(ledgerId, condition),
            tagMerchantFilterConditionFingerprint(ledgerId, condition.copy(merchantId = null)),
        )
        assertNotEquals(
            tagMerchantFilterConditionFingerprint(ledgerId, condition),
            tagMerchantFilterConditionFingerprint(LedgerId("ledger-other"), condition),
        )
        // A changed fingerprint is what makes an old-condition landing drop (spec 5.3): the
        // empty condition's fingerprint differs from every non-empty one.
        assertNotEquals(
            tagMerchantFilterConditionFingerprint(ledgerId, TagMerchantFilterCondition()),
            tagMerchantFilterConditionFingerprint(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA))),
        )
    }

    // ---- OR/AND pure matching (spec section 5.1) ----

    @Test
    fun orMatchesAnySelectedTagAndAndRequiresEverySelectedTag() {
        val both = entry("tx-1", 10_000L, tagIds = listOf(tagA, tagB))
        val onlyA = entry("tx-2", 10_000L, tagIds = listOf(tagA))
        val none = entry("tx-3", 10_000L, tagIds = emptyList())

        val or = TagMerchantFilterCondition(tagIds = setOf(tagA, tagB), tagMatch = TagMerchantTagMatchMode.Any)
        assertTrue(tagMerchantConditionMatches(or, both))
        assertTrue(tagMerchantConditionMatches(or, onlyA))
        assertTrue(!tagMerchantConditionMatches(or, none))

        val and = TagMerchantFilterCondition(tagIds = setOf(tagA, tagB), tagMatch = TagMerchantTagMatchMode.All)
        assertTrue(tagMerchantConditionMatches(and, both))
        assertTrue(!tagMerchantConditionMatches(and, onlyA))
        assertTrue(!tagMerchantConditionMatches(and, none))

        // 无标签条件 = 全部： an empty condition matches even a never-annotated entry.
        assertTrue(tagMerchantConditionMatches(TagMerchantFilterCondition(), none))
        assertTrue(tagMerchantConditionMatches(TagMerchantFilterCondition(), entry("tx-4", 10_000L, tagIds = emptyList(), merchantId = null)))
    }

    @Test
    fun merchantConditionAndsWithTagConditionsAndNeverGuessesFromNames() {
        val merchantOne = MerchantId("merchant-1")
        val taggedMerchantOne = entry("tx-1", 10_000L, tagIds = listOf(tagA), merchantId = merchantOne)
        val untaggedMerchantOne = entry("tx-2", 10_000L, tagIds = emptyList(), merchantId = merchantOne)
        val taggedMerchantTwo = entry("tx-3", 10_000L, tagIds = listOf(tagA), merchantId = MerchantId("merchant-2"))

        val condition = TagMerchantFilterCondition(tagIds = setOf(tagA), merchantId = merchantOne)
        assertTrue(tagMerchantConditionMatches(condition, taggedMerchantOne))
        // AND: an untagged transaction of the same merchant is not matched.
        assertTrue(!tagMerchantConditionMatches(condition, untaggedMerchantOne))
        // AND: the same tag on another merchant is not matched — matching is by stable id,
        // never by any display-name coincidence.
        assertTrue(!tagMerchantConditionMatches(condition, taggedMerchantTwo))
    }

    // ---- A01 group folding: overlap full amounts, groups never additive ----

    @Test
    fun a01GroupsShowFullOverlapTotalsAndAreNeverAdditive() {
        // The A01 vector: three transactions of 100 each — tx1 carries 甲+乙, tx2 only 甲,
        // tx3 only 乙. The matched set (OR over 甲乙) folds per tag: 甲组 = tx1+tx2 = 200,
        // 乙组 = tx1+tx3 = 200; the two groups overlap on tx1 and MUST NOT be added (200+200
        // is not any total of the ledger — the matched set itself is 300 counted once).
        val tx1 = entry("tx-1", 10_000L, tagIds = listOf(tagA, tagB))
        val tx2 = entry("tx-2", 10_000L, tagIds = listOf(tagA))
        val tx3 = entry("tx-3", 10_000L, tagIds = listOf(tagB))
        val matched = listOf(tx1, tx2, tx3)

        val groups = foldTagMerchantGroups(matched, selectedTagIds = setOf(tagA, tagB), ledgerId = ledgerId, catalog = catalog())
        assertEquals(200_00L, groups.getValue(tagA)[cny])
        assertEquals(200_00L, groups.getValue(tagB)[cny])
        assertTrue(
            groups.getValue(tagA)[cny]!! + groups.getValue(tagB)[cny]!! != 300_00L,
            "the two overlapping groups must not add up to the matched set's total",
        )

        // Under AND only tx1 matches: BOTH groups show the same single transaction's 100.
        val andMatched = matched.filter { tagMerchantConditionMatches(TagMerchantFilterCondition(tagIds = setOf(tagA, tagB), tagMatch = TagMerchantTagMatchMode.All), it) }
        val andGroups = foldTagMerchantGroups(andMatched, selectedTagIds = setOf(tagA, tagB), ledgerId = ledgerId, catalog = catalog())
        assertEquals(100_00L, andGroups.getValue(tagA)[cny])
        assertEquals(100_00L, andGroups.getValue(tagB)[cny])
    }

    @Test
    fun groupCountsAreIndependentAndAnUnmatchedSelectedTagIsAbsent() {
        val tx1 = entry("tx-1", 10_000L, tagIds = listOf(tagA))
        val groups = foldTagMerchantGroups(listOf(tx1), selectedTagIds = setOf(tagA, tagB), ledgerId = ledgerId, catalog = catalog())
        assertEquals(1, tagMerchantGroupTransactionCount(listOf(tx1), tagA))
        assertEquals(0, tagMerchantGroupTransactionCount(listOf(tx1), tagB))
        // 乙 matches nothing: no fabricated zero group row (absent, not 0).
        assertTrue(!groups.containsKey(tagB))
        assertEquals(100_00L, groups.getValue(tagA)[cny])
    }

    // ---- merchant accumulation reuses the frozen ordinary classifier (spec 5.4) ----

    @Test
    fun merchantAccumulationCountsOnlyExplicitlyAssociatedRefundsThroughTheFrozenClassifier() {
        // A06 refund semantics: a refund counts ONLY when the refund transaction's OWN
        // annotation names the merchant; the fold is the frozen MonthlyBuckets.aggregate, so
        // a REFUND_RECEIPT's negative expense leg enters the merchant's net expense as a
        // negative contribution (P708-A06 intent; the read-side vectors live in ledger-data).
        val merchantOne = MerchantId("merchant-1")
        val expense = entry("tx-expense", 50_00L, tagIds = emptyList(), merchantId = merchantOne)
        val refund =
            entry(
                "tx-refund",
                -20_00L,
                tagIds = emptyList(),
                merchantId = merchantOne,
                kind = TransactionKind.REFUND_RECEIPT,
            )
        val rows = listOf(expense.row, refund.row)
        val result = accumulationUseCase(rows).query(ledgerId, merchantOne, march, expectedCatalogItemVersion = 3L)
        val success = assertIs<MerchantMonthlyAccumulationResult.Success>(result)
        assertEquals(30_00L, success.netExpenseByCurrency[cny])
        assertEquals(3L, success.catalogItemVersion)
        // The special-consumption exclusion rides the frozen classifier: a STORED_VALUE_SPEND
        // row annotated to the merchant contributes ZERO to the ordinary net expense.
        val storedValueSpend =
            entry(
                "tx-stored-value",
                10_00L,
                tagIds = emptyList(),
                merchantId = merchantOne,
                kind = TransactionKind.STORED_VALUE_SPEND,
            )
        val withSpecial =
            accumulationUseCase(listOf(expense.row, refund.row, storedValueSpend.row)).query(ledgerId, merchantOne, march, expectedCatalogItemVersion = 3L)
        assertEquals(30_00L, assertIs<MerchantMonthlyAccumulationResult.Success>(withSpecial).netExpenseByCurrency[cny])
    }

    @Test
    fun merchantAccumulationFailsClosedOnPortFailuresAndBadCatalogs() {
        val merchantOne = MerchantId("merchant-1")
        // Every port failure maps verbatim — never a zero (spec 5.3).
        for (code in TagMerchantFilterFailureCode.entries) {
            val result = accumulationUseCasePort(FailingPort(code)).query(ledgerId, merchantOne, march, expectedCatalogItemVersion = 1L)
            assertEquals(code, assertIs<MerchantMonthlyAccumulationResult.Failed>(result).failureCode)
        }
        // A posting account missing from the catalog is the typed bad-catalog failure.
        val orphanRow =
            entry("tx-orphan", 10_00L, tagIds = emptyList(), merchantId = merchantOne, expenseAccountId = AccountId("account-unknown"))
        val badCatalog = accumulationUseCase(listOf(orphanRow.row)).query(ledgerId, merchantOne, march, expectedCatalogItemVersion = 1L)
        assertEquals(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG, assertIs<MerchantMonthlyAccumulationResult.Failed>(badCatalog).failureCode)
    }

    @Test
    fun merchantAccumulationEmptyMonthIsAGenuineZero() {
        val merchantOne = MerchantId("merchant-1")
        // A configured merchant with no matched rows in the month is a real zero, not a failure.
        val result = accumulationUseCase(emptyList()).query(ledgerId, merchantOne, march, expectedCatalogItemVersion = 1L)
        val success = assertIs<MerchantMonthlyAccumulationResult.Success>(result)
        // An absent currency reads as the genuine zero (the BudgetMonthContribution contract).
        assertEquals(0L, success.netExpenseByCurrency[cny] ?: 0L)
        assertEquals(0, success.transactionCount)
    }

    // ---- fixtures ----

    private val march = YearMonth(2026, 3)

    private fun entry(
        transactionId: String,
        amountMinor: Long,
        tagIds: List<TagId>,
        merchantId: MerchantId? = null,
        kind: TransactionKind = TransactionKind.EXPENSE,
        expenseAccountId: AccountId = expenseId,
    ): TagMerchantFilterEntry {
        val statisticsAt = "2026-03-10T02:00:00Z"
        return TagMerchantFilterEntry(
            row =
                LedgerEntryRow(
                    transactionId = TransactionId(transactionId),
                    currentVersionId = TransactionVersionId("version-$transactionId"),
                    kind = kind,
                    occurredAt = Instant.parse(statisticsAt),
                    statisticsAt = Instant.parse(statisticsAt),
                    note = null,
                    postings =
                        listOf(
                            Posting(PostingId("posting-$transactionId-expense"), expenseAccountId, Money.ofMinor(amountMinor, cny)),
                            Posting(PostingId("posting-$transactionId-payment"), assetId, Money.ofMinor(-amountMinor, cny)),
                        ),
                ),
            tagIds = tagIds,
            merchantId = merchantId,
        )
    }

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(expenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories = listOf(Category(CategoryId("category-1"), ledgerId, parentId = null, postingAccountId = expenseId, active = true)),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }

    private fun accumulationUseCase(rows: List<LedgerEntryRow>): QueryMerchantMonthlyAccumulation =
        QueryMerchantMonthlyAccumulation(
            object : MerchantAccumulationReadPort {
                override fun readMerchantRows(
                    ledgerId: LedgerId,
                    merchantId: MerchantId,
                    startInclusive: Instant,
                    endExclusive: Instant,
                    expectedCatalogItemVersion: Long,
                ): MerchantAccumulationReadResult =
                    MerchantAccumulationReadResult.Success(
                        catalogItemVersion = expectedCatalogItemVersion,
                        catalog = catalog(),
                        rows = rows,
                    )
            },
        )

    private fun accumulationUseCasePort(port: MerchantAccumulationReadPort): QueryMerchantMonthlyAccumulation = QueryMerchantMonthlyAccumulation(port)

    private class FailingPort(
        private val code: TagMerchantFilterFailureCode,
    ) : MerchantAccumulationReadPort {
        override fun readMerchantRows(
            ledgerId: LedgerId,
            merchantId: MerchantId,
            startInclusive: Instant,
            endExclusive: Instant,
            expectedCatalogItemVersion: Long,
        ): MerchantAccumulationReadResult = MerchantAccumulationReadResult.Failed(code)
    }
}
