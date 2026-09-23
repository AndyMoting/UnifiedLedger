package com.unifiedledger.ui

import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.RecycleBinDependencyLeg
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RecycleBinRow
import com.unifiedledger.application.VoidedTransactionRow
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05 slice 1b presentation-decision vectors (D-156; spec section 4.4; V-21). app-ui deliberately
 * has no Compose UI-test harness, so the load-bearing decisions are pure functions and are asserted
 * here: the detail-page entry gate (support matrix + effective + resolved origin), the field-by-field
 * difference preview (amount with sign and currency code, category/account current names, both time
 * zone semantics), the void/restore reason admissibility and the recycle-bin row text (reason, void
 * time, dependency explanation, restore admissibility). All data is anonymous synthetic with fixed
 * instants; no platform or Compose API is exercised. V-21: this suite never prints a reason note in
 * a failure message — it asserts on composed strings only.
 */
class P503CorrectionPresentationTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val statisticsAt = Instant.parse("2026-03-15T02:00:00Z")
    private val transactionId = TransactionId("tx-1")
    private val versionId = TransactionVersionId("version-1")

    // ---- detail-page entry gate (DP-12; spec section 3.5) ---------------------------------

    @Test
    fun detailEntriesShowOnlyForSupportedEffectiveTransactions() {
        // EXPENSE with an effective state and a resolved origin: both entries.
        assertEquals(
            DetailCorrectionAffordances(edit = true, void = true),
            detailCorrectionAffordancesVisible(TransactionKind.EXPENSE, CreationEntry.MANUAL_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true),
        )
        // INCOME is the other first-slice matrix row.
        assertTrue(
            detailCorrectionAffordancesVisible(TransactionKind.INCOME, CreationEntry.MANUAL_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true).edit,
        )
        // UNMARKED is the other supported creation entry of the slice (spec section 2.2).
        assertTrue(
            detailCorrectionAffordancesVisible(TransactionKind.EXPENSE, CreationEntry.UNMARKED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true).any,
        )
    }

    @Test
    fun detailEntriesHideForUnsupportedKindsOrVoidedOrUnresolved() {
        // Transfer/lending/refund are outside the first-slice support matrix (spec section 3.1).
        assertFalse(
            detailCorrectionAffordancesVisible(TransactionKind.ACCOUNT_TRANSFER, CreationEntry.MANUAL_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true).any,
        )
        assertFalse(
            detailCorrectionAffordancesVisible(TransactionKind.LEND, CreationEntry.MANUAL_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true).any,
        )
        // D-158 section 4: the remaining later-slice matrix rows hide the entries too.
        listOf(
            TransactionKind.REFUND_RECEIPT,
            TransactionKind.CREDIT_REPAYMENT,
            TransactionKind.COLLECT,
            TransactionKind.OPENING_BALANCE,
            TransactionKind.STORED_VALUE_RECHARGE,
            TransactionKind.PREPAID_PURCHASE,
        ).forEach { kind ->
            assertFalse(
                detailCorrectionAffordancesVisible(kind, CreationEntry.MANUAL_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true).any,
                "kind $kind must offer no correction entry",
            )
        }
        // A voided transaction (defensive echo of the read invariant) offers nothing.
        assertFalse(
            detailCorrectionAffordancesVisible(TransactionKind.EXPENSE, CreationEntry.MANUAL_CREATED, isEffective = false, hasCorrectionOrigin = true, hasVoidTarget = true).any,
        )
        // No resolved origin / no host affordance: no dead button.
        assertEquals(
            DetailCorrectionAffordances(edit = false, void = false),
            detailCorrectionAffordancesVisible(TransactionKind.EXPENSE, CreationEntry.MANUAL_CREATED, isEffective = true, hasCorrectionOrigin = false, hasVoidTarget = false),
        )
        // The two entries gate independently.
        assertEquals(
            DetailCorrectionAffordances(edit = true, void = false),
            detailCorrectionAffordancesVisible(TransactionKind.EXPENSE, CreationEntry.MANUAL_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = false),
        )
    }

    @Test
    fun detailEntriesHideForImportCreatedTransactions() {
        // F1/V-13: import-created EXPENSE/INCOME is explicitly a later slice (DP-5). The detail
        // payload carries the authoritative lineage, so the gate must consume it — an
        // import-created transaction offers no entry even though its kind is supported.
        assertFalse(
            detailCorrectionAffordancesVisible(TransactionKind.EXPENSE, CreationEntry.IMPORT_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true).any,
        )
        assertFalse(
            detailCorrectionAffordancesVisible(TransactionKind.INCOME, CreationEntry.IMPORT_CREATED, isEffective = true, hasCorrectionOrigin = true, hasVoidTarget = true).any,
        )
        // The lineage half of the matrix in isolation.
        assertTrue(isP705CreationLineageSupported(CreationEntry.MANUAL_CREATED))
        assertTrue(isP705CreationLineageSupported(CreationEntry.UNMARKED))
        assertFalse(isP705CreationLineageSupported(CreationEntry.IMPORT_CREATED))
    }

    // ---- difference preview (spec section 4.4) --------------------------------------------

    @Test
    fun diffPreviewRendersAmountWithSignAndCurrencyAndNamesAndBothTimes() {
        val preview =
            TransactionEditPreview(
                fields =
                    listOf(
                        TransactionEditFieldDiff(TransactionEditField.NOTE, oldText = "旧备注", newText = "新备注"),
                        TransactionEditFieldDiff(TransactionEditField.STATISTICS_AT, oldText = statisticsAt.toString(), newText = "2026-04-01T00:00:00Z"),
                        TransactionEditFieldDiff(TransactionEditField.AMOUNT, oldText = "100.00", newText = "80.00"),
                        TransactionEditFieldDiff(TransactionEditField.CATEGORY, oldText = "category-food", newText = "category-transport"),
                        TransactionEditFieldDiff(TransactionEditField.FUNDING_ACCOUNT, oldText = "asset-payment", newText = "asset-cash"),
                    ),
            )
        val lines =
            transactionEditDiffLines(
                preview = preview,
                categoryNames = mapOf(CategoryId("category-food") to "餐饮", CategoryId("category-transport") to "交通"),
                accountNames = mapOf(AccountId("asset-payment") to "零钱", AccountId("asset-cash") to "现金"),
                currency = cny,
            )
        assertEquals(listOf("备注", "统计时间", "金额", "分类", "资金账户"), lines.map { it.label })
        // Amount carries an explicit sign and the currency code.
        assertEquals("+100.00 CNY", lines[2].oldText)
        assertEquals("+80.00 CNY", lines[2].newText)
        // Category/account show their CURRENT names, never the bare id.
        assertEquals("餐饮", lines[3].oldText)
        assertEquals("交通", lines[3].newText)
        assertEquals("零钱", lines[4].oldText)
        assertEquals("现金", lines[4].newText)
        // Both time-zone semantics: the Asia/Shanghai wall clock with the annotation next to the
        // UTC ISO instant (2026-03-15T02:00:00Z = 10:00 +08:00).
        assertTrue(lines[1].oldText.startsWith("2026-03-15 10:00（UTC+8）＝ 2026-03-15T02:00:00Z"), lines[1].oldText)
        assertEquals("2026-04-01 08:00（UTC+8）＝ 2026-04-01T00:00:00Z", lines[1].newText)
        // Changed flags follow the old/new difference.
        assertTrue(lines[0].changed)
        assertTrue(lines[2].changed)
    }

    @Test
    fun diffPreviewShowsAbsentReferencesAndVerbatimUnparseableAmount() {
        val preview =
            TransactionEditPreview(
                fields =
                    listOf(
                        TransactionEditFieldDiff(TransactionEditField.NOTE, oldText = "", newText = ""),
                        TransactionEditFieldDiff(TransactionEditField.CATEGORY, oldText = "", newText = "category-unknown"),
                        TransactionEditFieldDiff(TransactionEditField.FUNDING_ACCOUNT, oldText = "", newText = ""),
                        TransactionEditFieldDiff(TransactionEditField.AMOUNT, oldText = "100.00", newText = "not-a-number"),
                    ),
            )
        val lines = transactionEditDiffLines(preview, categoryNames = emptyMap(), accountNames = emptyMap(), currency = cny)
        // An absent reference and an empty note read as 无.
        assertEquals("无", lines[0].oldText)
        assertEquals("无", lines[1].oldText)
        // A reference the catalog no longer offers keeps its stable id (never silently blanked).
        assertEquals("category-unknown", lines[1].newText)
        assertEquals("无", lines[2].newText)
        // An unparseable amount is shown verbatim — the preview never normalizes silently.
        assertEquals("not-a-number", lines[3].newText)
        // The confirmation statement is the frozen one (spec section 4.4).
        assertEquals("确认后：历史版本保留、旧版本失效。", TRANSACTION_EDIT_CONFIRM_STATEMENT)
    }

    @Test
    fun correctionAmountDisplayPreservesSignAndNegativeValues() {
        assertEquals("+100.00 CNY", correctionAmountDisplayText("100.00", cny))
        assertEquals("-12.34 CNY", correctionAmountDisplayText("-12.34", cny))
        assertEquals("无", correctionAmountDisplayText("", cny))
    }

    // ---- reason admissibility (DP-11) ------------------------------------------------------

    @Test
    fun reasonDraftAdmissibilityFollowsTheDomainRule() {
        // No code: the reason is mandatory.
        assertEquals(P705FailureCode.P705_VOID_REASON_REQUIRED, voidReasonDraftRejection(VoidReasonDraft()))
        // A typed code without a note is admissible.
        assertNull(voidReasonDraftRejection(VoidReasonDraft(code = VoidReasonCode.MIS_ENTERED)))
        // A whitespace-only note is still a missing reason (the domain's blank rule).
        assertEquals(
            P705FailureCode.P705_VOID_REASON_REQUIRED,
            voidReasonDraftRejection(VoidReasonDraft(code = VoidReasonCode.OTHER, note = "   ")),
        )
        // A bounded note is admissible.
        assertNull(voidReasonDraftRejection(VoidReasonDraft(code = VoidReasonCode.OTHER, note = "说明")))
    }

    @Test
    fun reasonDisplayTextCoversAllFrozenCodes() {
        assertEquals("录入错误", voidReasonCodeLabel(VoidReasonCode.MIS_ENTERED))
        assertEquals("重复录入", voidReasonCodeLabel(VoidReasonCode.DUPLICATE_ENTRY))
        assertEquals("不再适用", voidReasonCodeLabel(VoidReasonCode.NO_LONGER_APPLICABLE))
        assertEquals("误作废", voidReasonCodeLabel(VoidReasonCode.VOIDED_IN_ERROR))
        assertEquals("其他", voidReasonCodeLabel(VoidReasonCode.OTHER))
        assertEquals("录入错误（笔误）", voidReasonDisplayText(VoidReason(VoidReasonCode.MIS_ENTERED, "笔误")))
        assertEquals("其他", voidReasonDisplayText(VoidReason(VoidReasonCode.OTHER)))
    }

    // ---- recycle-bin row text (spec section 4.4; V-21) ------------------------------------

    @Test
    fun recycleBinRowTextShowsReasonVoidTimeAndDependencies() {
        val row = binRow(restoreRejectionCode = null)
        val text = recycleBinRowText(row)
        assertEquals("EXPENSE tx-1", text.title)
        assertEquals("录入错误（笔误）", text.reason)
        // The void time uses the same both-time-zone rendering as the preview.
        assertTrue(text.voidTime.startsWith("2026-03-16 10:00（UTC+8）＝ 2026-03-16T02:00:00Z"), text.voidTime)
        assertTrue(text.dependencies.contains("零钱（可用）"), text.dependencies)
        assertTrue(text.dependencies.contains("餐饮"), text.dependencies)
        assertTrue(text.dependencies.contains("无有效关联退款"), text.dependencies)
        assertTrue(text.dependencies.contains("手工创建"), text.dependencies)
        assertEquals("可恢复", text.restoreStatus)
        // One announced node carries every value.
        assertTrue(text.contentDescription.contains(text.reason))
        assertTrue(text.contentDescription.contains(text.voidTime))
    }

    @Test
    fun recycleBinRowTextStatesInadmissibleRestoreWithItsTypedCode() {
        val row = binRow(restoreRejectionCode = P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
        assertFalse(row.restoreAdmissible)
        val text = recycleBinRowText(row)
        assertTrue(text.restoreStatus.startsWith("不可恢复："), text.restoreStatus)
        assertTrue(text.restoreStatus.contains(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE.code), text.restoreStatus)
    }

    @Test
    fun restoreVerdictNeverPresentsTheVoidOnlyRefundCode() {
        // F2 / D-158 section 4: QueryRecycleBin folds the void-only
        // P705_REFUND_LINKED_VOID_NOT_SUPPORTED into restoreRejectionCode, but restore never checks
        // it. The presentation layer must not show it as a restore verdict; the row falls back to
        // the admissible presentation and offers the restore affordance.
        val row = binRow(restoreRejectionCode = P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED)
        assertNull(recycleBinRestoreVerdict(row))
        assertTrue(recycleBinRestoreAdmissible(row))
        assertEquals("可恢复", recycleBinRowText(row).restoreStatus)
        // A genuine restore rejection still passes through verbatim.
        val rejected = binRow(restoreRejectionCode = P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            recycleBinRestoreVerdict(rejected),
        )
    }

    @Test
    fun recycleBinDependencyLegShowsDeactivatedReferencesExplicitly() {
        val leg =
            RecycleBinDependencyLeg(
                postingId = PostingId("posting-1"),
                accountId = AccountId("asset-payment"),
                accountName = "零钱",
                accountActive = false,
                categoryName = "餐饮",
                categoryActive = false,
            )
        val text = recycleBinDependencyLegText(leg)
        assertTrue(text.contains("零钱（已停用）"), text)
        assertTrue(text.contains("餐饮（已停用）"), text)
    }

    @Test
    fun recycleBinFailureAndEmptyCopiesStayDistinct() {
        assertNull(recycleBinFailureText(RecycleBinResult.Success(emptyList())))
        assertEquals("回收站数据不一致，无法展示。", recycleBinFailureText(RecycleBinResult.InvalidState))
        assertEquals("无法读取回收站（本地数据库不可用）。", recycleBinFailureText(RecycleBinResult.Unavailable))
        assertTrue(RECYCLE_BIN_EMPTY_TEXT.isNotEmpty())
    }

    // ---- manual re-check status line (V-19; D-173) -----------------------------------------

    @Test
    fun recheckStatusTextIsAbsentForNoneAndDistinctForTheStillUnknownOutcomes() {
        // NONE keeps the plain 「正在提交…」 line: no extra status text (Fix 3).
        assertNull(p705RecheckStatusText(P705CommitCheckOutcome.NONE))
        // ABSENT / UNAVAILABLE each tell the user the re-check ran and found nothing, so the
        // resolved-but-empty outcome is never a silent no-op.
        assertEquals("未找到该次提交记录，可再次核对", p705RecheckStatusText(P705CommitCheckOutcome.ABSENT))
        assertEquals("暂时无法核对，请稍后重试", p705RecheckStatusText(P705CommitCheckOutcome.UNAVAILABLE))
        // The two still-unknown copies stay distinct (a reader can tell "no record" from "unreadable").
        assertTrue(p705RecheckStatusText(P705CommitCheckOutcome.ABSENT) != p705RecheckStatusText(P705CommitCheckOutcome.UNAVAILABLE))
    }

    // ---- fixtures -------------------------------------------------------------------------

    private fun binRow(restoreRejectionCode: P705FailureCode?): RecycleBinRow =
        RecycleBinRow(
            voided =
                VoidedTransactionRow(
                    transactionId = transactionId,
                    currentVersionId = versionId,
                    kind = TransactionKind.EXPENSE,
                    occurredAt = statisticsAt,
                    statisticsAt = statisticsAt,
                    note = null,
                    postings =
                        listOf(
                            Posting(
                                id = PostingId("posting-1"),
                                accountId = AccountId("asset-payment"),
                                amount = Money.ofMinor(10000L, cny),
                            ),
                        ),
                    voidFactKind = TransactionVoidFactKind.VOID,
                    voidReason = VoidReason(VoidReasonCode.MIS_ENTERED, "笔误"),
                    voidedAt = Instant.parse("2026-03-16T02:00:00Z"),
                ),
            creationEntry = CreationEntry.MANUAL_CREATED,
            dependencies =
                listOf(
                    RecycleBinDependencyLeg(
                        postingId = PostingId("posting-1"),
                        accountId = AccountId("asset-payment"),
                        accountName = "零钱",
                        accountActive = true,
                        categoryName = "餐饮",
                        categoryActive = true,
                    ),
                ),
            hasEffectiveRefundLink = false,
            restoreRejectionCode = restoreRejectionCode,
        )
}
