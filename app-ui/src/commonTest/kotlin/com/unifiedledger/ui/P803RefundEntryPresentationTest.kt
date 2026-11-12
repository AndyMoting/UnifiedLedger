package com.unifiedledger.ui

import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ManualRefundCandidate
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.application.ManualRefundRejectionCode
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-03 i2b (D-312; spec sections 2.2/2.3/2.6) — the refund entry's pure presentation layer.
 *
 * V-1: the type list and its labels; V-10: the delivered type set is exactly the six and no
 * undelivered type ever appears; V-13: the candidate list's four states are distinct, a failed read
 * is never presented as an empty list and only the failure offers a retry; D-312/spec section 2.6: every rejection code
 * has its own frozen sentence.
 */
class P803RefundEntryPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    // ---- V-1: the type list ----

    @Test
    fun theTypeListOffersTheRefundOnlyWhenTheSurfaceIsWired() {
        assertEquals(
            listOf(EntryType.EXPENSE, EntryType.INCOME, EntryType.TRANSFER, EntryType.LEND, EntryType.COLLECT),
            entryTypeChoices(refundEntryAvailable = false),
        )
        assertFalse(EntryType.REFUND_RECEIPT in entryTypeChoices(refundEntryAvailable = false))

        val wired = entryTypeChoices(refundEntryAvailable = true)
        assertEquals(6, wired.size)
        assertTrue(EntryType.REFUND_RECEIPT in wired)
        // The refund is the LAST choice, so the five pre-P8-03 types keep their frozen order.
        assertEquals(EntryType.REFUND_RECEIPT, wired.last())
        assertEquals(entryTypeChoices(refundEntryAvailable = false), wired.dropLast(1))
    }

    @Test
    fun theRefundTypeLabelIsFrozen() {
        assertEquals("退款到账", entryTypeLabel(EntryType.REFUND_RECEIPT))
        // The five pre-existing labels are unchanged and still exhaustive over the enum.
        assertEquals(
            listOf("支出", "收入", "转账", "借出", "收回", "退款到账"),
            EntryType.entries.map(::entryTypeLabel),
        )
    }

    // ---- V-10: no undelivered type appears ----

    @Test
    fun noUndeliveredEntryTypeAppearsInTheListOrItsLabels() {
        // The five undelivered types of the design gate (balance adjustment, mixed payment,
        // instalment, image evidence, credit-card billing) have no enum member at all, so the
        // delivered set is exactly these six — a fixed list no future addition can silently join.
        val delivered =
            listOf(
                EntryType.EXPENSE,
                EntryType.INCOME,
                EntryType.TRANSFER,
                EntryType.LEND,
                EntryType.COLLECT,
                EntryType.REFUND_RECEIPT,
            )
        assertEquals(delivered, EntryType.entries)
        assertEquals(delivered, entryTypeChoices(refundEntryAvailable = true))
    }

    // ---- V-13: the candidate list's four states ----

    @Test
    fun theCandidateViewDistinguishesNotReadFromEmptyFromFailed() {
        assertEquals(RefundCandidatesView.Loading, refundCandidatesView(null))
        assertEquals(
            RefundCandidatesView.Empty,
            refundCandidatesView(ManualRefundCandidatesResult.Loaded(emptyList(), hasMore = false)),
        )
        assertEquals(RefundCandidatesView.Failed, refundCandidatesView(ManualRefundCandidatesResult.Unavailable))
    }

    @Test
    fun aFailedReadIsNeverPresentedAsAnEmptyList() {
        val empty = refundCandidatesView(ManualRefundCandidatesResult.Loaded(emptyList(), hasMore = false))
        val failed = refundCandidatesView(ManualRefundCandidatesResult.Unavailable)
        assertTrue(empty != failed, "an empty page and a read failure must stay distinct")
        assertEquals(REFUND_CANDIDATES_EMPTY_TEXT, refundCandidatesNotice(empty))
        assertEquals(REFUND_CANDIDATES_FAILED_TEXT, refundCandidatesNotice(failed))
        assertTrue(
            refundCandidatesNotice(empty) != refundCandidatesNotice(failed),
            "the empty and failure copies must differ",
        )
        assertEquals(REFUND_CANDIDATES_LOADING_TEXT, refundCandidatesNotice(RefundCandidatesView.Loading))
        assertNull(refundCandidatesNotice(RefundCandidatesView.Loaded(listOf(candidate()))))
    }

    @Test
    fun onlyAFailedReadOffersTheRetry() {
        assertTrue(refundCandidatesRetryable(refundCandidatesView(ManualRefundCandidatesResult.Unavailable)))
        assertFalse(refundCandidatesRetryable(refundCandidatesView(null)))
        assertFalse(refundCandidatesRetryable(refundCandidatesView(ManualRefundCandidatesResult.Loaded(emptyList(), hasMore = false))))
        assertFalse(
            refundCandidatesRetryable(
                refundCandidatesView(ManualRefundCandidatesResult.Loaded(listOf(candidate()), hasMore = true)),
            ),
        )
    }

    @Test
    fun theCandidateRowsAreTheBoundedPageOfTheReadResult() {
        val rows = listOf(candidate(), candidate(transactionId = "tx-2"))
        val view = refundCandidatesView(ManualRefundCandidatesResult.Loaded(rows, hasMore = true))
        assertEquals(RefundCandidatesView.Loaded(rows), view)
    }

    @Test
    fun oneCandidateRowShowsDateCategoryCapRefundedAndRemaining() {
        assertEquals(
            "2026-01-15 · 餐饮 · 支出腿金额 100.00 · 已退 20.00 · 剩余 80.00",
            refundCandidateRowText(candidate()),
        )
    }

    @Test
    fun theRowDerivesTheAlreadyRefundedPartInExactMinorUnits() {
        // cap 0.30 - remaining 0.10: binary floating point would print 0.19999999999999998.
        val candidate =
            candidate(
                structuralCapMinor = 30L,
                remainingMinor = 10L,
            )
        assertEquals(
            "2026-01-15 · 餐饮 · 支出腿金额 0.30 · 已退 0.20 · 剩余 0.10",
            refundCandidateRowText(candidate),
        )
    }

    // ---- D-312/spec section 2.6: the frozen rejection copy ----

    @Test
    fun everyRejectionCodeHasItsOwnFrozenSentence() {
        val expected =
            mapOf(
                ManualRefundRejectionCode.REFUND_CATEGORY_INELIGIBLE to "退款分类不可用",
                ManualRefundRejectionCode.REFUND_ACCOUNT_INELIGIBLE to "到账账户不可用",
                ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE to "原交易当前不可退",
                ManualRefundRejectionCode.ORIGINAL_NO_LONGER_REFUNDABLE to "原交易已被作废或修改，请重新选择",
                ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP to "退款金额超过原交易的支出金额",
                ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING to "退款金额超过剩余可退金额",
                ManualRefundRejectionCode.REFUND_REQUEST_CONFLICT to "该请求与已有记录不一致，请重新进入",
                ManualRefundRejectionCode.REFUND_STORE_UNAVAILABLE to "退款保存失败，请稍后重试",
            )
        // The closed set has exactly eight codes; each maps to its own sentence (no shared fallback).
        assertEquals(expected.keys, ManualRefundRejectionCode.entries.toSet())
        for ((code, text) in expected) {
            assertEquals(text, refundRejectionText(code), "frozen copy of $code")
        }
        assertEquals(expected.size, expected.values.toSet().size, "no two codes share a sentence")
    }

    @Test
    fun theUnknownOutcomeSentenceIsTheSpecVerbatim() {
        assertEquals("结果未知，请重开确认", REFUND_UNKNOWN_RESULT_TEXT)
    }

    // ---- fixtures ----

    private fun candidate(
        transactionId: String = "tx-original",
        structuralCapMinor: Long = 10_000L,
        remainingMinor: Long = 8_000L,
    ): ManualRefundCandidate =
        ManualRefundCandidate(
            transactionId = TransactionId(transactionId),
            occurredAt = occurredAt,
            categoryId = CategoryId("expense-leaf"),
            categoryName = "餐饮",
            structuralCap = Money.ofMinor(structuralCapMinor, cny),
            remaining = Money.ofMinor(remainingMinor, cny),
        )
}
