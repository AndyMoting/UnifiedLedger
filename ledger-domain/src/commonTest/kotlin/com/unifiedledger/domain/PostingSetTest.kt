package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PostingSetTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val assetId = AccountId("asset-bank-a")
    private val equityId = AccountId("equity-opening-a")

    @Test
    fun acceptsAnExactlyBalancedPostingSet() {
        val result =
            PostingSet.create(
                id = PostingSetId("posting-set-balanced"),
                postings =
                    listOf(
                        Posting(PostingId("posting-asset"), assetId, money(10_001, cny)),
                        Posting(PostingId("posting-equity"), equityId, money(-10_001, cny)),
                    ),
            )

        assertEquals(
            setOf(PostingId("posting-asset"), PostingId("posting-equity")),
            success(result).postings.map { it.id }.toSet(),
        )
    }

    @Test
    fun balancesCurrenciesSeparately() {
        val result =
            PostingSet.create(
                id = PostingSetId("posting-set-cross-currency"),
                postings =
                    listOf(
                        Posting(PostingId("posting-cny"), assetId, money(100, cny)),
                        Posting(PostingId("posting-usd"), equityId, money(-100, usd)),
                    ),
            )

        assertEquals(DomainViolation.UnbalancedPostingSet, failure(result))
    }

    @Test
    fun rejectsAnUnbalancedSameCurrencyPostingSet() {
        val result =
            PostingSet.create(
                id = PostingSetId("posting-set-unbalanced"),
                postings =
                    listOf(
                        Posting(PostingId("posting-asset"), assetId, money(10_001, cny)),
                        Posting(PostingId("posting-equity"), equityId, money(-10_000, cny)),
                    ),
            )

        assertEquals(DomainViolation.UnbalancedPostingSet, failure(result))
    }

    @Test
    fun acceptsBalancedExtremeValuesRegardlessOfPostingOrder() {
        val overflowFirst =
            PostingSet.create(
                id = PostingSetId("posting-set-extreme-overflow-first"),
                postings =
                    listOf(
                        Posting(PostingId("posting-extreme-a-max"), assetId, money(Long.MAX_VALUE, cny)),
                        Posting(PostingId("posting-extreme-a-one"), assetId, money(1L, cny)),
                        Posting(PostingId("posting-extreme-a-min"), assetId, money(Long.MIN_VALUE, cny)),
                    ),
            )
        val cancellationFirst =
            PostingSet.create(
                id = PostingSetId("posting-set-extreme-cancellation-first"),
                postings =
                    listOf(
                        Posting(PostingId("posting-extreme-b-max"), assetId, money(Long.MAX_VALUE, cny)),
                        Posting(PostingId("posting-extreme-b-min"), assetId, money(Long.MIN_VALUE, cny)),
                        Posting(PostingId("posting-extreme-b-one"), assetId, money(1L, cny)),
                    ),
            )

        success(overflowFirst)
        success(cancellationFirst)
    }
}
