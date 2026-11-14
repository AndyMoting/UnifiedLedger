package com.unifiedledger.application.import.selfentry

import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-04 i3a (D-18) typed per-class evidence: exact-name matching over a real
 * [LedgerCatalog.create] fixture, one assertion per class.
 *
 * Must-carry classes (spec section 4 i3): the same-name ambiguity counterexample (two
 * same-named leaves -> AMBIGUOUS), the full-path disambiguation positive (父A/子X vs 父B/子X
 * resolved by the parent name), the same-name account ambiguity, the exact-match discipline
 * (trim/whitespace-fold yes, case-folding and substring no — D-032 never guesses), the
 * transfer's unconsumed-category-text flag, and the inactive-row exclusion. Ambiguity is
 * handled as no match (D-18); matching never decides (D-017) and the inputs never enter the
 * content fingerprint (session data, D-016) — both documented on the matcher.
 */
class SelfEntryNameMatchJvmTest {
    private val ledgerId = LedgerId("ledger-p804-i3a")

    // ---------------------------------------------------------------- fixture builders

    private fun assetAccount(
        id: String,
        name: String,
        active: Boolean = true,
        ownedByUser: Boolean = true,
        realAccount: Boolean = true,
    ): Account =
        Account(
            id = AccountId(id),
            ledgerId = ledgerId,
            kind = AccountKind.ASSET,
            currency = CurrencyUnit("CNY", 2),
            ownedByUser = ownedByUser,
            realAccount = realAccount,
            name = name,
            active = active,
        )

    private fun leaf(
        id: String,
        name: String,
        parentId: String?,
        kind: CategoryKind = CategoryKind.EXPENSE,
        active: Boolean = true,
    ): Category =
        Category(
            id = CategoryId(id),
            ledgerId = ledgerId,
            parentId = parentId?.let { CategoryId(it) },
            postingAccountId = null,
            active = active,
            kind = kind,
            name = name,
        )

    private fun catalog(
        accounts: List<Account>,
        categories: List<Category>,
    ): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts, categories)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> throw IllegalStateException("fixture catalog rejected: ${result.violation}")
        }

    private fun matcher(catalog: LedgerCatalog): SelfEntryNameMatcher = SelfEntryNameMatcher(catalog)

    private fun ordinary(inputs: SelfEntryMatchInputs): SelfEntryMatchOutcome = matcher(fixtureCatalog()).match(inputs, ImportRecordKind.ORDINARY_FLOW_SOURCE, "out")

    private fun income(inputs: SelfEntryMatchInputs): SelfEntryMatchOutcome = matcher(fixtureCatalog()).match(inputs, ImportRecordKind.ORDINARY_FLOW_SOURCE, "in")

    private fun transfer(inputs: SelfEntryMatchInputs): SelfEntryMatchOutcome = matcher(fixtureCatalog()).match(inputs, ImportRecordKind.TRANSFER_FLOW_SOURCE, "out")

    /**
     * The shared fixture: 父A/父B both own a leaf 子X (the same-name pair), a unique leaf
     * 餐饮, two same-named funding accounts, and inactive rows of every shape.
     */
    private fun fixtureCatalog(): LedgerCatalog =
        catalog(
            accounts =
                listOf(
                    assetAccount("acc-cash", "现金"),
                    assetAccount("acc-bank", "银行卡"),
                    assetAccount("acc-wallet-a", "钱包"),
                    assetAccount("acc-wallet-b", "钱包"),
                    assetAccount("acc-closed", "定期存款", active = false),
                ),
            categories =
                listOf(
                    leaf("cat-a", "父A", null),
                    leaf("cat-b", "父B", null),
                    leaf("cat-a-x", "子X", "cat-a"),
                    leaf("cat-b-x", "子X", "cat-b"),
                    leaf("cat-dup-1", "同名叶", "cat-a"),
                    leaf("cat-dup-2", "同名叶", "cat-b"),
                    leaf("cat-food", "餐饮", "cat-a"),
                    leaf("cat-salary", "工资", null, kind = CategoryKind.INCOME),
                    leaf("cat-food-dead", "餐饮", "cat-b", active = false),
                ),
        )

    // ---------------------------------------------------------------- ordinary rows: category resolution

    @Test
    fun aUniqueLeafNameResolvesThroughBareLeafMatching() {
        val outcome = ordinary(SelfEntryMatchInputs("餐饮", "", "现金", ""))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(CategoryId("cat-food"), matched.categoryId)
        assertEquals("餐饮", matched.categoryName)
        assertEquals(AccountId("acc-cash"), matched.fundingAccountId)
        assertEquals("现金", matched.fundingAccountName)
        assertNull(matched.fromAccountId)
        assertNull(matched.toAccountId)
        assertFalse(matched.unconsumedCategoryText)
    }

    @Test
    fun twoSameNamedLeavesAreAmbiguousEvenWithABlankSecondary() {
        val outcome = ordinary(SelfEntryMatchInputs("同名叶", "", "现金", ""))

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.CATEGORY_AMBIGUOUS, unmatched.categoryReason)
        assertNull(unmatched.fundingAccountReason)
        assertFalse(unmatched.unconsumedCategoryText)
    }

    @Test
    fun theFullPathDisambiguatesSameNamedLeaves() {
        val outcome = ordinary(SelfEntryMatchInputs("父A", "子X", "现金", ""))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(CategoryId("cat-a-x"), matched.categoryId)
        assertEquals("子X", matched.categoryName)
    }

    @Test
    fun theSiblingFullPathDisambiguatesToTheOtherSameNamedLeaf() {
        val outcome = ordinary(SelfEntryMatchInputs("父B", "子X", "现金", ""))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(CategoryId("cat-b-x"), matched.categoryId)
    }

    @Test
    fun aFailedParentNameFallsBackToBareLeafMatching() {
        // 父C does not exist; 子X alone is still ambiguous (two same-named leaves) — the
        // fallback lands on the ambiguity, not on a silent single-leg match.
        val outcome = ordinary(SelfEntryMatchInputs("父C", "子X", "现金", ""))

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.CATEGORY_AMBIGUOUS, unmatched.categoryReason)
    }

    @Test
    fun aFailedParentNameStillResolvesAUniqueLeafName() {
        // 父C does not exist, but 餐饮 is the unique leaf of its kind — the fallback still
        // resolves it; a wrong parent never poisons a resolvable leaf.
        val outcome = ordinary(SelfEntryMatchInputs("父C", "餐饮", "现金", ""))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(CategoryId("cat-food"), matched.categoryId)
        assertEquals("餐饮", matched.categoryName)
    }

    @Test
    fun anUnknownCategoryNameIsNotFoundAndAnUnknownAccountStaysNotFoundToo() {
        val outcome = ordinary(SelfEntryMatchInputs("不存在的分类", "", "不存在账户", ""))

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.CATEGORY_NOT_FOUND, unmatched.categoryReason)
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND, unmatched.fundingAccountReason)
    }

    @Test
    fun anIncomeRowResolvesAgainstTheIncomeKindOnly() {
        val outcome = income(SelfEntryMatchInputs("工资", "", "银行卡", ""))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(CategoryId("cat-salary"), matched.categoryId)
        assertEquals(AccountId("acc-bank"), matched.fundingAccountId)
    }

    @Test
    fun anExpenseRowNeverResolvesAnIncomeKindLeaf() {
        val outcome = ordinary(SelfEntryMatchInputs("工资", "", "现金", ""))

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.CATEGORY_NOT_FOUND, unmatched.categoryReason)
    }

    @Test
    fun blankCellsCarryNoTargetWithoutFailingTheRow() {
        val outcome = ordinary(SelfEntryMatchInputs("", "", "", ""))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertNull(matched.categoryId)
        assertNull(matched.categoryName)
        assertNull(matched.fundingAccountId)
        assertNull(matched.fundingAccountName)
        assertFalse(matched.unconsumedCategoryText)
    }

    // ---------------------------------------------------------------- exact-match discipline (D-032)

    @Test
    fun surroundingWhitespaceIsTrimmedBeforeTheExactComparison() {
        val outcome = ordinary(SelfEntryMatchInputs(" 餐饮 ", "", " 现金 ", ""))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(CategoryId("cat-food"), matched.categoryId)
        assertEquals(AccountId("acc-cash"), matched.fundingAccountId)
    }

    @Test
    fun internalWhitespaceRunsFoldToOneSpaceButNeverTheReverse() {
        // The catalog carries the folded canonical form; the cell's single space matches it.
        val foldedCatalog =
            catalog(
                accounts = listOf(assetAccount("acc-cjk-space", "交通 银行")),
                categories = emptyList(),
            )
        val matched =
            assertIs<SelfEntryMatchOutcome.Matched>(
                matcher(foldedCatalog).match(
                    SelfEntryMatchInputs("", "", "交通\t银行", ""),
                    ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    "out",
                ),
            )
        assertEquals(AccountId("acc-cjk-space"), matched.fundingAccountId)
    }

    @Test
    fun aSubstringIsNeverAFuzzyMatch() {
        // Both catalog names properly CONTAIN the input text; exact-name matching still
        // returns NOT_FOUND for each — a substring is never a fuzzy match (D-032).
        val substringCatalog =
            catalog(
                accounts = listOf(assetAccount("acc-bank", "交通银行")),
                categories = listOf(leaf("cat-transport", "交通补贴", null)),
            )
        val outcome = matcher(substringCatalog).match(SelfEntryMatchInputs("交通", "", "交通", ""), ImportRecordKind.ORDINARY_FLOW_SOURCE, "out")

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.CATEGORY_NOT_FOUND, unmatched.categoryReason)
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND, unmatched.fundingAccountReason)
    }

    @Test
    fun asciiNamesAreMatchedCaseSensitively() {
        val asciiCatalog =
            catalog(
                accounts = listOf(assetAccount("acc-card", "Card")),
                categories = emptyList(),
            )
        val matcher = matcher(asciiCatalog)
        val matched =
            assertIs<SelfEntryMatchOutcome.Matched>(
                matcher.match(SelfEntryMatchInputs("", "", "Card", ""), ImportRecordKind.ORDINARY_FLOW_SOURCE, "out"),
            )
        assertEquals(AccountId("acc-card"), matched.fundingAccountId)
        val unmatched =
            assertIs<SelfEntryMatchOutcome.Unmatched>(
                matcher.match(SelfEntryMatchInputs("", "", "card", ""), ImportRecordKind.ORDINARY_FLOW_SOURCE, "out"),
            )
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND, unmatched.fundingAccountReason)
    }

    // ---------------------------------------------------------------- account resolution

    @Test
    fun twoSameNamedFundingAccountsAreAmbiguous() {
        val outcome = ordinary(SelfEntryMatchInputs("餐饮", "", "钱包", ""))

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_AMBIGUOUS, unmatched.fundingAccountReason)
        assertNull(unmatched.categoryReason)
    }

    @Test
    fun anInactiveAccountIsNeverMatched() {
        val outcome = ordinary(SelfEntryMatchInputs("餐饮", "", "定期存款", ""))

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND, unmatched.fundingAccountReason)
    }

    @Test
    fun anInactiveLeafIsNeverMatchedWhileItsActiveTwinWins() {
        // 餐饮 exists twice: the active cat-a leaf and the inactive cat-b leaf. The inactive
        // row drops out, so exactly one active candidate remains — a precise match, and the
        // negative control (the inactive one alone) stays NOT_FOUND on the other parent.
        val matched =
            assertIs<SelfEntryMatchOutcome.Matched>(
                matcher(fixtureCatalog()).match(
                    SelfEntryMatchInputs("父A", "餐饮", "现金", ""),
                    ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    "out",
                ),
            )
        assertEquals(CategoryId("cat-food"), matched.categoryId)
        val notFound =
            assertIs<SelfEntryMatchOutcome.Unmatched>(
                matcher(
                    catalog(
                        accounts = listOf(assetAccount("acc-only", "现金")),
                        categories = listOf(leaf("cat-b", "父B", null), leaf("cat-food-dead", "餐饮", "cat-b", active = false)),
                    ),
                ).match(
                    SelfEntryMatchInputs("父B", "餐饮", "现金", ""),
                    ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    "out",
                ),
            )
        assertEquals(SelfEntryMatchUnresolvedReason.CATEGORY_NOT_FOUND, notFound.categoryReason)
    }

    // ---------------------------------------------------------------- transfer rows (D-18)

    @Test
    fun aTransferResolvesItsLegsAndNeverConsumesTheCategory() {
        val outcome = transfer(SelfEntryMatchInputs("转账", "还信用卡", "现金", "银行卡"))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(AccountId("acc-cash"), matched.fromAccountId)
        assertEquals("现金", matched.fromAccountName)
        assertEquals(AccountId("acc-bank"), matched.toAccountId)
        assertEquals("银行卡", matched.toAccountName)
        assertNull(matched.categoryId)
        assertNull(matched.fundingAccountId)
        assertTrue(matched.unconsumedCategoryText, "the transfer's non-blank category text must surface, never be silently ignored")
    }

    @Test
    fun aTransferWithBlankCategoryTextCarriesNoUnconsumedFlag() {
        val outcome = transfer(SelfEntryMatchInputs("", "", "现金", "银行卡"))

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertTrue(matched.unconsumedCategoryText.not())
    }

    @Test
    fun aTransferLegFailureIsCarriedPerLeg() {
        val outcome = transfer(SelfEntryMatchInputs("", "", "不存在账户", "钱包"))

        val unmatched = assertIs<SelfEntryMatchOutcome.Unmatched>(outcome)
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND, unmatched.fromAccountReason)
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_AMBIGUOUS, unmatched.toAccountReason)
        assertNull(unmatched.categoryReason)
        assertNull(unmatched.fundingAccountReason)
    }

    @Test
    fun aMissingLegTransferWithABlankAccount2CarriesNoLegFailure() {
        val outcome =
            matcher(fixtureCatalog()).match(
                SelfEntryMatchInputs("", "", "现金", ""),
                ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG,
                "out",
            )

        val matched = assertIs<SelfEntryMatchOutcome.Matched>(outcome)
        assertEquals(AccountId("acc-cash"), matched.fromAccountId)
        assertNull(matched.toAccountId)
        assertNull(matched.toAccountName)
    }

    // ---------------------------------------------------------------- basis projection (D-19 carrier)

    @Test
    fun theBasisCarriesThePathSourceTextAndTheTargetNames() {
        val outcome = ordinary(SelfEntryMatchInputs("父A", "子X", "现金", ""))
        val basis = outcome.toMatchBasis(SelfEntryMatchInputs("父A", "子X", "现金", ""))

        assertEquals("父A/子X", basis.categorySourceText)
        assertEquals("子X", basis.categoryTargetName)
        assertNull(basis.categoryUnmatchedReason)
        assertEquals("现金", basis.fundingAccountSourceText)
        assertEquals("现金", basis.fundingAccountTargetName)
        assertNull(basis.fundingAccountUnmatchedReason)
        assertFalse(basis.unconsumedCategoryText)
    }

    @Test
    fun theBasisCarriesTheTypedReasonTokensAndBlankSourceTexts() {
        val unmatched =
            SelfEntryMatchOutcome.Unmatched(
                categoryReason = SelfEntryMatchUnresolvedReason.CATEGORY_AMBIGUOUS,
                fundingAccountReason = null,
                fromAccountReason = null,
                toAccountReason = SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND,
                unconsumedCategoryText = false,
            )
        val basis = unmatched.toMatchBasis(SelfEntryMatchInputs("同名叶", "", "", ""))

        assertEquals(SelfEntryMatchUnresolvedReason.CATEGORY_AMBIGUOUS.name, basis.categoryUnmatchedReason)
        assertNull(basis.categoryTargetName)
        assertNull(basis.fundingAccountUnmatchedReason)
        assertEquals("", basis.fundingAccountSourceText)
        assertEquals(SelfEntryMatchUnresolvedReason.ACCOUNT_NOT_FOUND.name, basis.toAccountUnmatchedReason)
        assertEquals("", basis.toAccountSourceText)
    }

    // ---------------------------------------------------------------- kind domain

    @Test
    fun aV3KindIsAFailLoudProgrammingErrorNeverASilentMatch() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                matcher(fixtureCatalog()).match(
                    SelfEntryMatchInputs("餐饮", "", "现金", ""),
                    ImportRecordKind.CREDIT_EXPENSE_SOURCE,
                    "out",
                )
            }
        assertTrue(failure.message!!.contains("CREDIT_EXPENSE_SOURCE"))
    }
}
