package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.SelfEntryMatchBasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-04 i3b (D-19/D-7; spec section 4 i3 后半): the candidate detail's read-only match-basis
 * area and note display slot, one branch per copy decision. The area's data rides the
 * session-memory maps (never persisted — the rendering predicate's null face is the
 * restart/no-catalog/no-self-entry face); the copy is pure so it asserts on the JVM.
 */
class P804MatchBasisPresentationTest {
    private fun matchedBasis(
        categorySourceText: String = "餐饮",
        categoryTargetName: String? = "餐饮",
        categoryMatchMethod: String? = "exact",
        fundingSourceText: String = "现金",
        fundingTargetName: String? = "现金",
        unconsumedCategoryText: Boolean = false,
    ): SelfEntryMatchBasis =
        SelfEntryMatchBasis(
            categorySourceText = categorySourceText,
            categoryTargetName = categoryTargetName,
            categoryUnmatchedReason = null,
            fundingAccountSourceText = fundingSourceText,
            fundingAccountTargetName = fundingTargetName,
            fundingAccountUnmatchedReason = null,
            fromAccountSourceText = "",
            fromAccountTargetName = null,
            fromAccountUnmatchedReason = null,
            toAccountSourceText = "",
            toAccountTargetName = null,
            toAccountUnmatchedReason = null,
            unconsumedCategoryText = unconsumedCategoryText,
            categoryMatchMethod = categoryMatchMethod,
        )

    // ---------------------------------------------------------------- rendering predicate (D-19)

    @Test
    fun aBasisInTheSessionMapRendersTheArea() {
        val bases = mapOf(ImportCandidateId("candidate-1") to matchedBasis())
        assertTrue(importDetailShowsMatchBasis(bases, ImportCandidateId("candidate-1")))
    }

    @Test
    fun noBasisLeavesTheWholeAreaUnrendered() {
        // The non-self-entry format / catalog-less session / restart face: no entry for the
        // candidate — the area renders nothing (it is session display data only).
        val bases = mapOf(ImportCandidateId("candidate-1") to matchedBasis())
        assertFalse(importDetailShowsMatchBasis(bases, ImportCandidateId("candidate-2")))
        assertFalse(importDetailShowsMatchBasis(emptyMap(), ImportCandidateId("candidate-1")))
    }

    // ---------------------------------------------------------------- category line (D-19)

    @Test
    fun anExactCategoryMatchRendersTheTargetWithTheExactMethod() {
        assertEquals("分类：餐饮 → 餐饮（精确）", importMatchBasisCategoryLine(matchedBasis()))
    }

    @Test
    fun aPathDisambiguatedCategoryMatchRendersThePathMethod() {
        val basis = matchedBasis(categorySourceText = "父A/子X", categoryTargetName = "子X", categoryMatchMethod = "path")
        assertEquals("分类：父A/子X → 子X（路径消歧）", importMatchBasisCategoryLine(basis))
    }

    @Test
    fun aMatchedCategoryWithoutAMethodTokenRendersBareTarget() {
        // The method is display-only; an unknown/absent token never invents a copy.
        val basis = matchedBasis(categoryMatchMethod = null)
        assertEquals("分类：餐饮 → 餐饮", importMatchBasisCategoryLine(basis))
    }

    @Test
    fun anUnmatchedCategoryRendersTheTypedReasonToken() {
        val basis = matchedBasis(categoryTargetName = null, categoryMatchMethod = null)
        val unmatched =
            basis.copy(
                categoryTargetName = null,
                categoryUnmatchedReason = "CATEGORY_NOT_FOUND",
                fundingAccountTargetName = null,
                fundingAccountUnmatchedReason = "ACCOUNT_AMBIGUOUS",
            )
        assertEquals("分类：餐饮 —— 未匹配（CATEGORY_NOT_FOUND）", importMatchBasisCategoryLine(unmatched))
    }

    // ---------------------------------------------------------------- full area lines (D-19)

    @Test
    fun theAreaLinesRenderEveryResolvedSlotAndSkipBlankSlots() {
        val lines = importMatchBasisLines(matchedBasis())
        assertEquals(
            listOf(
                "分类：餐饮 → 餐饮（精确）",
                "资金账户：现金 → 现金",
            ),
            lines,
        )
    }

    @Test
    fun anUnmatchedRowRendersItsPerSlotReasonLines() {
        val basis = matchedBasis(categoryTargetName = null, categoryMatchMethod = null)
        val lines =
            importMatchBasisLines(
                basis.copy(
                    categoryTargetName = null,
                    categoryUnmatchedReason = "CATEGORY_AMBIGUOUS",
                    fundingAccountTargetName = null,
                    fundingAccountUnmatchedReason = "ACCOUNT_NOT_FOUND",
                ),
            )
        assertEquals(
            listOf(
                "分类：餐饮 —— 未匹配（CATEGORY_AMBIGUOUS）",
                "资金账户：现金 —— 未匹配（ACCOUNT_NOT_FOUND）",
            ),
            lines,
        )
    }

    @Test
    fun aTransferRendersTheUnconsumedFlagAndItsLegsNotTheFundingSlot() {
        // The transfer's 账户1 text is mirrored into the funding slot structurally (the arm's
        // projection), so the area renders the legs — the funding slot stays unrendered and
        // the category text surfaces only as the 不消费 line.
        val basis =
            SelfEntryMatchBasis(
                categorySourceText = "转账",
                categoryTargetName = null,
                categoryUnmatchedReason = null,
                fundingAccountSourceText = "现金",
                fundingAccountTargetName = null,
                fundingAccountUnmatchedReason = null,
                fromAccountSourceText = "现金",
                fromAccountTargetName = "现金",
                fromAccountUnmatchedReason = null,
                toAccountSourceText = "银行卡",
                toAccountTargetName = "银行卡",
                toAccountUnmatchedReason = null,
                unconsumedCategoryText = true,
            )
        val lines = importMatchBasisLines(basis)
        assertEquals(
            listOf(
                "转账行不消费类别文本",
                "转出账户：现金 → 现金",
                "转入账户：银行卡 → 银行卡",
            ),
            lines,
        )
    }

    @Test
    fun aTransferWithABlankLegRendersOnlyTheResolvedLegAndTheFlag() {
        val basis =
            SelfEntryMatchBasis(
                categorySourceText = "",
                categoryTargetName = null,
                categoryUnmatchedReason = null,
                fundingAccountSourceText = "现金",
                fundingAccountTargetName = null,
                fundingAccountUnmatchedReason = null,
                fromAccountSourceText = "现金",
                fromAccountTargetName = "现金",
                fromAccountUnmatchedReason = null,
                toAccountSourceText = "",
                toAccountTargetName = null,
                toAccountUnmatchedReason = null,
                unconsumedCategoryText = false,
            )
        val lines = importMatchBasisLines(basis)
        assertEquals(listOf("转出账户：现金 → 现金"), lines)
    }

    @Test
    fun aFullyBlankMatchedBasisRendersNoSlotLines() {
        // Blank cells are no match and no failure (D-18): nothing to show on any slot.
        val basis = matchedBasis(categorySourceText = "", categoryTargetName = null, fundingSourceText = "", fundingTargetName = null)
        assertTrue(importMatchBasisLines(basis).isEmpty())
    }

    // ---------------------------------------------------------------- note slot (D-7)

    @Test
    fun theNoteSlotRendersTheRemarkPrefixed() {
        val notes = mapOf(ImportCandidateId("candidate-1") to "网上买菜")
        assertEquals("备注：网上买菜", importDetailNoteLine(notes, ImportCandidateId("candidate-1")))
    }

    @Test
    fun aBlankNoteRendersNothing() {
        val notes = mapOf(ImportCandidateId("candidate-1") to "  ")
        assertNull(importDetailNoteLine(notes, ImportCandidateId("candidate-1")))
        assertNull(importDetailNoteLine(emptyMap(), ImportCandidateId("candidate-1")))
    }

    /**
     * F-L1 (D-7 independence): the note slot's rendering predicate is INDEPENDENT of the
     * match-basis area's — a catalog-less self-entry session carries the note without any
     * basis, so the note must render while the basis area stays unrendered.
     */
    @Test
    fun aNoteWithoutABasisRendersTheNoteAlone() {
        val candidateId = ImportCandidateId("candidate-1")
        val notes = mapOf(candidateId to "无 catalog 会话的备注")
        // The basis area's own predicate reads false for this candidate...
        assertFalse(importDetailShowsMatchBasis(emptyMap(), candidateId))
        // ...while the note slot's predicate reads true — the two gates are separate.
        assertTrue(importDetailShowsNote(notes, candidateId))
        assertEquals("备注：无 catalog 会话的备注", importDetailNoteLine(notes, candidateId))
    }

    @Test
    fun noNoteRendersNeitherSlotEvenWhenTheBasisExists() {
        val candidateId = ImportCandidateId("candidate-1")
        assertFalse(importDetailShowsNote(emptyMap(), candidateId))
        assertFalse(importDetailShowsNote(mapOf(candidateId to "  "), candidateId))
    }
}
