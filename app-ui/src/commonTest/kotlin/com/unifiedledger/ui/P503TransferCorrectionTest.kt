package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CategoryTreeView
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionReconciliationProjection
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05 transfer correction slice vectors (D-156, D-228; spec sections 3 and 8.3). The screen and
 * the entrance are delivered and asserted elsewhere (the host suite pins the entrance decision), so
 * the load-bearing decisions here are the pure functions: the leg-shape origin resolver, the seed
 * draft, the typed field write, the statistics-time seam, the seven-line difference preview, the
 * target-side fee-category derivation, the option list, and the request builder with its frozen five
 * `null` branches.
 *
 * All data is anonymous synthetic: one ledger, three manageable `ASSET` accounts plus the one
 * manageable `LIABILITY` account a single vector adds, two expense posting accounts (one of them the
 * fee category's own posting account), two categories, one transaction and two to four legs.
 * Assertions are single lines and never compare a multi-line literal; the suite asserts on synthetic
 * note literals in value equality checks only and never passes a note as an assertion message.
 *
 * Not signed off by this suite: the transfer void/restore face and the Android manual acceptance.
 * The snapshot-aware resolver's transfer columns are reconstructed on the read side (the
 * TransactionCorrectionRequestSnapshot contract), so no residual of that kind is carried here.
 */
class P503TransferCorrectionTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val jpy = CurrencyUnit("JPY", 0)
    private val requestId = RequestId("request-1")
    private val transactionId = TransactionId("tx-1")
    private val versionId = TransactionVersionId("version-1")
    private val statisticsAt = Instant.parse("2026-03-15T02:00:00Z")

    private val walletId = AccountId("asset-wallet")
    private val bankId = AccountId("asset-bank")
    private val cashId = AccountId("asset-cash")
    private val cardId = AccountId("liability-card")
    private val feePostingAccountId = AccountId("expense-fee")
    private val otherExpenseAccountId = AccountId("expense-other")
    private val feeCategoryId = CategoryId("category-fee")
    private val foodCategoryId = CategoryId("category-food")

    // ---- fixtures ----

    private val defaultCategories =
        listOf(
            CategoryTreeView(
                categoryId = feeCategoryId,
                parentId = null,
                name = "手续费",
                kind = CategoryKind.EXPENSE,
                active = true,
                postingAccountId = feePostingAccountId,
            ),
            CategoryTreeView(
                categoryId = foodCategoryId,
                parentId = null,
                name = "餐饮",
                kind = CategoryKind.EXPENSE,
                active = true,
                postingAccountId = null,
            ),
        )

    private val defaultAssetAccounts =
        listOf(
            ManageableAccountView(walletId, "零钱", AccountKind.ASSET, cny, active = true, balanceMinorUnits = null),
            ManageableAccountView(bankId, "储蓄", AccountKind.ASSET, cny, active = true, balanceMinorUnits = null),
            ManageableAccountView(cashId, "现金", AccountKind.ASSET, cny, active = true, balanceMinorUnits = null),
        )

    /**
     * The catalog snapshot under test: the three manageable `ASSET` accounts plus [extraAccounts], so a
     * vector can hold the observable projection fixed and vary only what the resolver admits from it.
     */
    private fun snapshot(
        categories: List<CategoryTreeView> = defaultCategories,
        extraAccounts: List<ManageableAccountView> = emptyList(),
    ): CatalogSnapshotView =
        CatalogSnapshotView(
            catalogVersion = 1L,
            manageableAccounts = defaultAssetAccounts + extraAccounts,
            categories = categories,
        )

    /** A manageable `LIABILITY` account: `isManageableAccount` admits it, a transfer endpoint does not. */
    private val liabilityAccount =
        ManageableAccountView(cardId, "信用卡", AccountKind.LIABILITY, cny, active = true, balanceMinorUnits = null)

    private fun leg(
        postingId: String,
        accountId: AccountId,
        amountMinorUnits: Long,
        currency: CurrencyUnit = cny,
    ): TransactionDetailLeg =
        TransactionDetailLeg(
            postingId = PostingId(postingId),
            accountId = accountId,
            accountName = accountId.value,
            amount = Money.ofMinor(amountMinorUnits, currency),
            categoryName = null,
        )

    private fun transferDetail(
        legs: List<TransactionDetailLeg>,
        kind: TransactionKind = TransactionKind.ACCOUNT_TRANSFER,
        note: String? = "old note",
    ): TransactionDetail =
        TransactionDetail(
            ledgerId = ledgerId,
            transactionId = transactionId,
            currentVersionId = versionId,
            kind = kind,
            occurredAt = statisticsAt,
            statisticsAt = statisticsAt,
            note = note,
            legs = legs,
            creationEntry = CreationEntry.MANUAL_CREATED,
            reconciliation = TransactionReconciliationProjection(legs = emptyList(), rollup = null),
        )

    /** The canonical three-leg transfer: source −102.00, destination +100.00, fee +2.00. */
    private fun feeBearingDetail(): TransactionDetail =
        transferDetail(
            listOf(
                leg("posting-source", walletId, -10_200L),
                leg("posting-destination", bankId, 10_000L),
                leg("posting-fee", feePostingAccountId, 200L),
            ),
        )

    /** The two-leg fee-less transfer: source −100.00, destination +100.00. */
    private fun feeLessDetail(): TransactionDetail =
        transferDetail(
            listOf(
                leg("posting-source", walletId, -10_000L),
                leg("posting-destination", bankId, 10_000L),
            ),
        )

    private fun resolvedOrigin(
        detail: TransactionDetail = feeBearingDetail(),
        catalog: CatalogSnapshotView = snapshot(),
    ): TransferCorrectionOrigin = assertNotNull(transferCorrectionOriginFromDetail(detail, catalog))

    private fun seedDraft(origin: TransferCorrectionOrigin): TransferCorrectionDraft = transferCorrectionDraftFromOrigin(origin)

    private fun previewOf(
        origin: TransferCorrectionOrigin,
        draft: TransferCorrectionDraft,
        categoryNames: Map<CategoryId, String> = emptyMap(),
        accountNames: Map<AccountId, String> = emptyMap(),
    ): TransferEditPreview = transferCorrectionPreview(origin, draft, categoryNames, accountNames)

    private fun row(
        preview: TransferEditPreview,
        field: TransferEditField,
    ): TransferEditDiffLine = preview.fields.first { it.field == field }

    private fun request(
        origin: TransferCorrectionOrigin,
        draft: TransferCorrectionDraft,
        statisticsAt: Instant = Instant.parse("2026-04-01T00:00:00Z"),
    ): ExplicitlyConfirmedTransactionCorrection? = transferCorrectionRequest(ledgerId, requestId, origin, draft, statisticsAt)

    // ---- T5-01..T5-03: origin resolution ----

    @Test
    fun originResolvesTheSourceDestinationAndFeeLegs() {
        val origin = assertNotNull(transferCorrectionOriginFromDetail(feeBearingDetail(), snapshot()))
        assertEquals(transactionId, origin.transactionId)
        assertEquals(versionId, origin.currentVersionId)
        assertEquals(statisticsAt, origin.statisticsAt)
        assertEquals("old note", origin.note)
        assertEquals(cny, origin.currency)
        assertEquals(walletId, origin.sourceAccountId)
        assertEquals(bankId, origin.destinationAccountId)
        assertEquals(10_000L, origin.destinationCreditMinorUnits)
        assertEquals(200L, origin.feeMinorUnits)
        assertEquals(feeCategoryId, origin.feeCategoryId)
    }

    @Test
    fun originResolvesAFeeLessTransferToZeroFeeAndNoCategory() {
        val origin = assertNotNull(transferCorrectionOriginFromDetail(feeLessDetail(), snapshot()))
        assertEquals(0L, origin.feeMinorUnits)
        assertNull(origin.feeCategoryId)
    }

    @Test
    fun originIsAbsentWhenTheLegShapeIsNotATransfer() {
        // (a) both legs positive: no negative ASSET leg is a source.
        assertNull(transferCorrectionOriginFromDetail(transferDetail(listOf(leg("posting-a", walletId, 10_000L), leg("posting-b", bankId, 10_000L))), snapshot()))
        // (b) both legs negative: no positive ASSET leg is a destination.
        assertNull(transferCorrectionOriginFromDetail(transferDetail(listOf(leg("posting-a", walletId, -10_000L), leg("posting-b", bankId, -10_000L))), snapshot()))
        // (c) two negative ASSET legs plus one positive: the source leg is ambiguous.
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(listOf(leg("posting-a", walletId, -10_000L), leg("posting-b", bankId, -10_000L), leg("posting-c", cashId, 10_000L))),
                snapshot(),
            ),
        )
        // (d) two positive ASSET legs and no fee leg: the destination leg is ambiguous.
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(listOf(leg("posting-a", cashId, -10_000L), leg("posting-b", walletId, 9_000L), leg("posting-c", bankId, 1_000L))),
                snapshot(),
            ),
        )
        // (e) a third leg whose account is no category's posting account: unexplained positive leg.
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(listOf(leg("posting-a", walletId, -10_200L), leg("posting-b", bankId, 10_000L), leg("posting-c", otherExpenseAccountId, 200L))),
                snapshot(),
            ),
        )
        // (f) the fee leg carries another currency than the destination leg.
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(listOf(leg("posting-a", walletId, -10_200L), leg("posting-b", bankId, 10_000L), leg("posting-c", feePostingAccountId, 200L, jpy))),
                snapshot(),
            ),
        )
        // (g) source and destination are the same account.
        assertNull(transferCorrectionOriginFromDetail(transferDetail(listOf(leg("posting-a", walletId, -10_200L), leg("posting-b", walletId, 10_000L))), snapshot()))
        // (h) the fee account maps to two categories: the fee category is ambiguous.
        assertNull(
            transferCorrectionOriginFromDetail(
                feeBearingDetail(),
                snapshot(
                    listOf(
                        CategoryTreeView(feeCategoryId, parentId = null, name = "手续费", kind = CategoryKind.EXPENSE, active = true, postingAccountId = feePostingAccountId),
                        CategoryTreeView(CategoryId("category-fee-2"), parentId = null, name = "手续费二", kind = CategoryKind.EXPENSE, active = true, postingAccountId = feePostingAccountId),
                    ),
                ),
            ),
        )
        // (i) an ordinary EXPENSE detail: its negative leg is not a manageable ASSET account.
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(listOf(leg("posting-a", feePostingAccountId, -10_000L), leg("posting-b", bankId, 10_000L)), kind = TransactionKind.EXPENSE),
                snapshot(),
            ),
        )
        // (j) four legs: the shape is neither two nor three legs.
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(
                    listOf(
                        leg("posting-a", walletId, -10_400L),
                        leg("posting-b", bankId, 10_000L),
                        leg("posting-c", feePostingAccountId, 200L),
                        leg("posting-d", cashId, 200L),
                    ),
                ),
                snapshot(),
            ),
        )
    }

    /**
     * The leg-count guard alone (`legs.size != 2 && legs.size != 3`). Case `(j)` above does not pin it:
     * those four legs also hold two positive `ASSET` legs, so the destination-leg ambiguity rejects the
     * row first. These four legs keep exactly one negative and one positive account inside the
     * manageable `ASSET` projection - the other two accounts sit outside it - so the leg-count guard is
     * the single reason this row resolves `null`.
     */
    @Test
    fun originIsAbsentForAFourLegShapeWhoseExtraLegsAreOutsideTheAssetProjection() {
        val detail =
            transferDetail(
                listOf(
                    leg("posting-source", walletId, -10_400L),
                    leg("posting-destination", bankId, 10_000L),
                    leg("posting-fee", feePostingAccountId, 200L),
                    leg("posting-other", otherExpenseAccountId, 200L),
                ),
            )
        assertNull(transferCorrectionOriginFromDetail(detail, snapshot()))
    }

    /**
     * The posting-id distinctness guard on its own: two legs share one posting id. The duplicate sits
     * on a fee-posting account outside the manageable `ASSET` projection, so the source/destination
     * reading alone would resolve this row and distinctness is the single reason it does not.
     */
    @Test
    fun originIsAbsentWhenTwoLegsShareOnePostingId() {
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(
                    listOf(
                        leg("posting-source", walletId, -10_200L),
                        leg("posting-destination", bankId, 10_000L),
                        leg("posting-destination", feePostingAccountId, 200L),
                    ),
                ),
                snapshot(),
            ),
        )
    }

    /**
     * A manageable `LIABILITY` account is not a transfer endpoint: `isManageableAccount` admits `ASSET`
     * and `LIABILITY` alike (`CatalogManagement.kt:152-160`), while the transfer endpoints require
     * `ASSET` (`AccountTransfer.kt:80`/`:135`/`:140`). A `LIABILITY` leg must therefore not resolve
     * here either - on the source side and on the destination side - so neither leg may be read
     * straight out of the manageable projection.
     */
    @Test
    fun originIsAbsentWhenALegAccountIsAManageableLiability() {
        val catalog = snapshot(extraAccounts = listOf(liabilityAccount))
        assertNull(transferCorrectionOriginFromDetail(transferDetail(listOf(leg("posting-a", cardId, -10_000L), leg("posting-b", bankId, 10_000L))), catalog))
        assertNull(transferCorrectionOriginFromDetail(transferDetail(listOf(leg("posting-a", walletId, -10_000L), leg("posting-b", cardId, 10_000L))), catalog))
    }

    /**
     * The fee leg's own positive-amount guard. A zero-amount third leg would otherwise resolve a fee of
     * `0` carrying the fee category, contradicting `feeCategoryId is null exactly when the fee is 0`.
     */
    @Test
    fun originIsAbsentWhenTheFeeLegAmountIsZero() {
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(
                    listOf(
                        leg("posting-source", walletId, -10_000L),
                        leg("posting-destination", bankId, 10_000L),
                        leg("posting-fee", feePostingAccountId, 0L),
                    ),
                ),
                snapshot(),
            ),
        )
    }

    /** The same guard's negative branch: a negative third leg is not a fee either. */
    @Test
    fun originIsAbsentWhenTheFeeLegAmountIsNegative() {
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(
                    listOf(
                        leg("posting-source", walletId, -8_000L),
                        leg("posting-destination", bankId, 10_000L),
                        leg("posting-fee", feePostingAccountId, -2_000L),
                    ),
                ),
                snapshot(),
            ),
        )
    }

    /**
     * The source-leg currency guard: every leg must carry the destination leg's currency, and a source
     * leg in another currency is the live case the fee-leg currency vector does not reach.
     */
    @Test
    fun originIsAbsentWhenTheSourceLegCurrencyDiffersFromTheDestinationLeg() {
        assertNull(
            transferCorrectionOriginFromDetail(
                transferDetail(listOf(leg("posting-a", walletId, -10_000L, jpy), leg("posting-b", bankId, 10_000L))),
                snapshot(),
            ),
        )
    }

    // ---- T5-04..T5-06: seed draft and preview ----

    @Test
    fun draftFromOriginShowsNoDifference() {
        val origin = resolvedOrigin()
        val draft = transferCorrectionDraftFromOrigin(origin)
        assertEquals("old note", draft.note)
        assertEquals(statisticsAt.toString(), draft.statisticsAtText)
        assertEquals("100.00", draft.destinationCreditText)
        assertEquals(walletId, draft.sourceAccountId)
        assertEquals(bankId, draft.destinationAccountId)
        assertEquals("2.00", draft.feeText)
        assertEquals(feeCategoryId, draft.feeCategoryId)
        val preview = transferCorrectionPreview(origin, draft, emptyMap(), emptyMap())
        assertEquals(7, preview.fields.size)
        assertTrue(preview.fields.none { it.changed })
    }

    /**
     * The old side of a preview row is read from the origin, never from the draft. Each row below
     * changes one draft field only, so a row whose old side echoed the draft would report the new text
     * twice and lose the origin text asserted here. The seeded draft above cannot show this: there both
     * sides are equal by construction.
     */
    @Test
    fun previewOldSideStaysTheOriginWhenTheDraftChanges() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        val changedCredit = row(previewOf(origin, draft.copy(destinationCreditText = "80.00")), TransferEditField.DESTINATION_CREDIT)
        assertEquals("+100.00 CNY", changedCredit.oldText)
        assertEquals("+80.00 CNY", changedCredit.newText)
        val changedFee = row(previewOf(origin, draft.copy(feeText = "3.00")), TransferEditField.FEE)
        assertEquals("+2.00 CNY", changedFee.oldText)
        assertEquals("+3.00 CNY", changedFee.newText)
        val changedNote = row(previewOf(origin, draft.copy(note = "new note")), TransferEditField.NOTE)
        assertEquals("old note", changedNote.oldText)
        assertEquals("new note", changedNote.newText)
        val changedStatisticsAt = row(previewOf(origin, draft.copy(statisticsAtText = "2026-04-01T00:00:00Z")), TransferEditField.STATISTICS_AT)
        assertEquals(correctionTimeDisplayText(origin.statisticsAt.toString()), changedStatisticsAt.oldText)
        assertEquals(correctionTimeDisplayText("2026-04-01T00:00:00Z"), changedStatisticsAt.newText)
    }

    @Test
    fun previewListsSevenFieldsInDisplayOrderWithLabels() {
        val origin = resolvedOrigin()
        val preview = previewOf(origin, seedDraft(origin))
        assertEquals(TransferEditField.entries.toList(), preview.fields.map { it.field })
        assertEquals(listOf("备注", "统计时间", "到账金额", "源账户", "目标账户", "手续费", "手续费分类"), preview.fields.map { it.label })
    }

    @Test
    fun previewResolvesCurrentNamesAndFallsBackToStableIds() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        val named = previewOf(origin, draft, mapOf(feeCategoryId to "手续费", foodCategoryId to "餐饮"), mapOf(walletId to "零钱", bankId to "储蓄"))
        assertEquals("零钱", row(named, TransferEditField.SOURCE_ACCOUNT).newText)
        assertEquals("零钱", row(named, TransferEditField.SOURCE_ACCOUNT).oldText)
        assertEquals("储蓄", row(named, TransferEditField.DESTINATION_ACCOUNT).newText)
        assertEquals("储蓄", row(named, TransferEditField.DESTINATION_ACCOUNT).oldText)
        assertEquals("手续费", row(named, TransferEditField.FEE_CATEGORY).newText)
        assertEquals("手续费", row(named, TransferEditField.FEE_CATEGORY).oldText)
        val unnamed = previewOf(origin, draft)
        assertEquals("asset-wallet", row(unnamed, TransferEditField.SOURCE_ACCOUNT).newText)
        assertEquals("asset-wallet", row(unnamed, TransferEditField.SOURCE_ACCOUNT).oldText)
        assertEquals("asset-bank", row(unnamed, TransferEditField.DESTINATION_ACCOUNT).newText)
        assertEquals("asset-bank", row(unnamed, TransferEditField.DESTINATION_ACCOUNT).oldText)
        assertEquals("category-fee", row(unnamed, TransferEditField.FEE_CATEGORY).newText)
        assertEquals("category-fee", row(unnamed, TransferEditField.FEE_CATEGORY).oldText)
        val feeLessOrigin = resolvedOrigin(feeLessDetail())
        val feeLess = previewOf(feeLessOrigin, seedDraft(feeLessOrigin))
        assertEquals("无", row(feeLess, TransferEditField.FEE_CATEGORY).oldText)
        assertEquals("无", row(feeLess, TransferEditField.FEE_CATEGORY).newText)
    }

    // ---- T5-07..T5-12: the request builder ----

    @Test
    fun requestCarriesTheTransferColumnsAndTheGivenStatisticsInstant() {
        val origin = resolvedOrigin()
        val draft =
            TransferCorrectionDraft(
                note = "new note",
                statisticsAtText = origin.statisticsAt.toString(),
                destinationCreditText = "80.00",
                sourceAccountId = walletId,
                destinationAccountId = bankId,
                feeText = "2.00",
                feeCategoryId = feeCategoryId,
            )
        val requested = assertNotNull(request(origin, draft, Instant.parse("2026-04-01T00:00:00Z")))
        assertEquals(ledgerId, requested.ledgerId)
        assertEquals(requestId, requested.requestId)
        assertEquals(transactionId, requested.transactionId)
        assertEquals(versionId, requested.expectedCurrentVersionId)
        assertEquals("new note", requested.note)
        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), requested.statisticsAt)
        assertEquals(8_000L, requested.amount.minorUnits)
        assertEquals(cny, requested.amount.currency)
        assertEquals(walletId, requested.fundingAccountId)
        assertEquals(bankId, requested.destinationAccountId)
        assertEquals(200L, requested.feeMinor)
        assertEquals(feeCategoryId, requested.categoryId)
        assertEquals(ExplicitManualSave, requested.confirmation)
        val emptiedNote = assertNotNull(request(origin, draft.copy(note = "")))
        assertNull(emptiedNote.note)
        val spacedNote = assertNotNull(request(origin, draft.copy(note = " ")))
        assertEquals(" ", spacedNote.note)
        val subSecondOrigin = origin.copy(statisticsAt = Instant.parse("2026-03-15T02:00:00.250Z"))
        val untouched = assertNotNull(request(subSecondOrigin, draft.copy(statisticsAtText = subSecondOrigin.statisticsAt.toString()), subSecondOrigin.statisticsAt))
        assertEquals(subSecondOrigin.statisticsAt, untouched.statisticsAt)
    }

    @Test
    fun requestIsAbsentWithoutADestinationAccount() {
        val origin = resolvedOrigin()
        assertNull(request(origin, seedDraft(origin).copy(destinationAccountId = null)))
    }

    @Test
    fun requestIsAbsentWithoutASourceAccount() {
        val origin = resolvedOrigin()
        assertNull(request(origin, seedDraft(origin).copy(sourceAccountId = null)))
    }

    @Test
    fun requestIsAbsentWhenTheDestinationCreditIsNotAnExactPositiveAmount() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        assertNull(request(origin, draft.copy(destinationCreditText = "0.00")))
        assertNull(request(origin, draft.copy(destinationCreditText = "-1.00")))
        assertNull(request(origin, draft.copy(destinationCreditText = "+80.00")))
        assertNull(request(origin, draft.copy(destinationCreditText = "not-a-number")))
        assertNull(request(origin, draft.copy(destinationCreditText = "")))
        assertNull(request(origin, draft.copy(destinationCreditText = "   ")))
        assertNull(request(origin, draft.copy(destinationCreditText = "1.005")))
        assertNull(request(origin, draft.copy(destinationCreditText = "80.005")))
        val relaxed = assertNotNull(request(origin, draft.copy(destinationCreditText = "80")))
        assertEquals(8_000L, relaxed.amount.minorUnits)
    }

    @Test
    fun requestIsAbsentWhenTheFeeIsNotAnExactNonNegativeAmount() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        assertNull(request(origin, draft.copy(feeText = "-0.01")))
        assertNull(request(origin, draft.copy(feeText = "not-a-number")))
        assertNull(request(origin, draft.copy(feeText = "")))
        assertNull(request(origin, draft.copy(feeText = "   ")))
        assertNull(request(origin, draft.copy(feeText = "0.001")))
        val relaxed = assertNotNull(request(origin, draft.copy(feeText = "2")))
        assertEquals(200L, relaxed.feeMinor)
    }

    @Test
    fun requestIsAbsentWhenAPositiveFeeCarriesNoFeeCategory() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        assertNull(request(origin, draft.copy(feeText = "2.00", feeCategoryId = null)))
        val zeroFee = assertNotNull(request(origin, draft.copy(feeText = "0.00", feeCategoryId = null)))
        assertEquals(0L, zeroFee.feeMinor)
        assertNull(zeroFee.categoryId)
    }

    /**
     * The frozen zero-fee requirement (spec sections 3/8.3; `ACCOUNTING_RULES.md:273`): driving the
     * fee to `0.00` while the draft still carries a fee category must drop that category from BOTH the
     * preview's target side and the request row, because the target-side category is derived from the
     * target fee and never read straight off the draft. The reversal at the end shows the drop is
     * specific to `fee == 0`, not a permanently lost category.
     */
    @Test
    fun aZeroFeeDropsTheResidualFeeCategoryInBothPreviewAndRequest() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin).copy(feeText = "0.00", feeCategoryId = feeCategoryId)
        assertNull(transferPreviewFeeCategoryId("0.00", feeCategoryId, cny))
        val preview = previewOf(origin, draft, mapOf(feeCategoryId to "手续费", foodCategoryId to "餐饮"), mapOf(walletId to "零钱", bankId to "储蓄"))
        assertEquals("无", row(preview, TransferEditField.FEE_CATEGORY).newText)
        assertEquals("手续费", row(preview, TransferEditField.FEE_CATEGORY).oldText)
        assertEquals("0.00 CNY", row(preview, TransferEditField.FEE).newText)
        val requested = assertNotNull(request(origin, draft))
        assertNull(requested.categoryId)
        assertEquals(0L, requested.feeMinor)
        assertEquals(10_000L, requested.amount.minorUnits)
        val restored = assertNotNull(request(origin, draft.copy(feeText = "2.00")))
        assertEquals(feeCategoryId, restored.categoryId)
    }

    /**
     * The target-side fee category follows the parsed target fee, not the draft: an unparseable text
     * yields no category (无), while a negative fee - which the builder rejects - keeps the category
     * the draft carries, because the preview never hides a reference the user typed. Only an exact
     * zero drops it.
     */
    @Test
    fun previewFeeCategoryIsAbsentForAnUnparseableFeeAndKeptForANegativeFee() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        val names = mapOf(feeCategoryId to "手续费")
        assertNull(transferPreviewFeeCategoryId("not-a-number", feeCategoryId, cny))
        val unparseable = row(previewOf(origin, draft.copy(feeText = "not-a-number", feeCategoryId = feeCategoryId), names), TransferEditField.FEE_CATEGORY)
        assertEquals("手续费", unparseable.oldText)
        assertEquals("无", unparseable.newText)
        assertEquals(feeCategoryId, transferPreviewFeeCategoryId("-0.01", feeCategoryId, cny))
        val negative = row(previewOf(origin, draft.copy(feeText = "-0.01", feeCategoryId = feeCategoryId), names), TransferEditField.FEE_CATEGORY)
        assertEquals("手续费", negative.oldText)
        assertEquals("手续费", negative.newText)
    }

    /**
     * Background (registered reason this vector replaced an earlier "preview implies admission" claim):
     * `P503CorrectionPresentation.kt:162-174` formats `"0.00"`/`"-1.00"` into display text (`:172` adds
     * a `+` only for a positive value), while the frozen builder rulings require those same values to
     * return `null`. "The preview can format it" therefore says nothing about admission, and this test
     * pins the two boundaries separately instead of coupling them:
     *
     * (i) echo boundary: a non-blank text the currency precision cannot parse is echoed verbatim AND
     * the builder returns `null`; a parseable text is always formatted AND the builder never returns
     * `null` for a parse failure;
     * (ii) parseable but inadmissible: a formatted preview with a `null` builder (zero and negative
     * amounts, a negative fee) - display is not an admission claim;
     * (iii) blank: the preview shows 无 and the builder returns `null` (blank is unparseable, not
     * "unchanged").
     */
    @Test
    fun previewEchoIsExactlyTheParseBoundaryAndNeverAnAdmissionClaim() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        val unparseable = draft.copy(destinationCreditText = "not-a-number")
        assertEquals("not-a-number", row(previewOf(origin, unparseable), TransferEditField.DESTINATION_CREDIT).newText)
        assertNull(request(origin, unparseable))
        val signed = draft.copy(destinationCreditText = "+80.00")
        assertEquals("+80.00", row(previewOf(origin, signed), TransferEditField.DESTINATION_CREDIT).newText)
        assertNull(request(origin, signed))
        val accepted = draft.copy(destinationCreditText = "80.00")
        assertEquals("+80.00 CNY", row(previewOf(origin, accepted), TransferEditField.DESTINATION_CREDIT).newText)
        assertNotNull(request(origin, accepted))
        val zero = draft.copy(destinationCreditText = "0.00")
        assertEquals("0.00 CNY", row(previewOf(origin, zero), TransferEditField.DESTINATION_CREDIT).newText)
        assertNull(request(origin, zero))
        val negative = draft.copy(destinationCreditText = "-1.00")
        assertEquals("-1.00 CNY", row(previewOf(origin, negative), TransferEditField.DESTINATION_CREDIT).newText)
        assertNull(request(origin, negative))
        val negativeFee = draft.copy(feeText = "-0.01", feeCategoryId = feeCategoryId)
        assertEquals("-0.01 CNY", row(previewOf(origin, negativeFee), TransferEditField.FEE).newText)
        assertNull(request(origin, negativeFee))
        val blank = draft.copy(destinationCreditText = "")
        assertEquals("无", row(previewOf(origin, blank), TransferEditField.DESTINATION_CREDIT).newText)
        assertNull(request(origin, blank))
    }

    /**
     * The frozen ruling that the builder has exactly five `null` branches: more admission rejection is
     * the domain layer's typed rejection, so none of these target states may silently degrade into a
     * sixth `null`. Each case still carries its column verbatim.
     */
    @Test
    fun requestDoesNotAddAdmissionChecksBeyondTheFiveNullBranches() {
        val origin = resolvedOrigin()
        val draft = seedDraft(origin)
        val sameAccount = assertNotNull(request(origin, draft.copy(sourceAccountId = bankId, destinationAccountId = bankId)))
        assertEquals(bankId, sameAccount.fundingAccountId)
        assertEquals(bankId, sameAccount.destinationAccountId)
        val offCatalogSource = AccountId("not-in-catalog-source")
        val offCatalogDestination = AccountId("not-in-catalog-destination")
        val offCatalog = assertNotNull(request(origin, draft.copy(sourceAccountId = offCatalogSource, destinationAccountId = offCatalogDestination)))
        assertEquals(offCatalogSource, offCatalog.fundingAccountId)
        assertEquals(offCatalogDestination, offCatalog.destinationAccountId)
        val unknownCategoryId = CategoryId("category-unknown")
        val unknownCategory = assertNotNull(request(origin, draft.copy(feeText = "2.00", feeCategoryId = unknownCategoryId)))
        assertEquals(unknownCategoryId, unknownCategory.categoryId)
        val feeLessOrigin = resolvedOrigin(feeLessDetail())
        val feeLessDraft = seedDraft(feeLessOrigin)
        val residue = assertNotNull(request(feeLessOrigin, feeLessDraft.copy(feeCategoryId = feeCategoryId)))
        assertNull(residue.categoryId)
        assertEquals(0L, residue.feeMinor)
    }

    // ---- the app-ui form seam (D-228): the typed field write and the statistics-time reading ----

    @Test
    fun everyTransferFieldUpdateWritesExactlyItsOwnField() {
        val seeded = seedDraft(resolvedOrigin())
        val note = seeded.withUpdate(TransferCorrectionFieldUpdate.Note("note text"))
        assertEquals("note text", note.note)
        assertEquals(seeded, note.copy(note = seeded.note))
        val statistics = seeded.withUpdate(TransferCorrectionFieldUpdate.StatisticsAt("2026-04-01T00:00:00Z"))
        assertEquals("2026-04-01T00:00:00Z", statistics.statisticsAtText)
        assertEquals(seeded, statistics.copy(statisticsAtText = seeded.statisticsAtText))
        val credit = seeded.withUpdate(TransferCorrectionFieldUpdate.DestinationCredit("80.00"))
        assertEquals("80.00", credit.destinationCreditText)
        assertEquals(seeded, credit.copy(destinationCreditText = seeded.destinationCreditText))
        val source = seeded.withUpdate(TransferCorrectionFieldUpdate.SourceAccount(cashId))
        assertEquals(cashId, source.sourceAccountId)
        assertEquals(seeded, source.copy(sourceAccountId = seeded.sourceAccountId))
        val destination = seeded.withUpdate(TransferCorrectionFieldUpdate.DestinationAccount(cashId))
        assertEquals(cashId, destination.destinationAccountId)
        assertEquals(seeded, destination.copy(destinationAccountId = seeded.destinationAccountId))
        val fee = seeded.withUpdate(TransferCorrectionFieldUpdate.Fee("0.00"))
        assertEquals("0.00", fee.feeText)
        assertEquals(seeded, fee.copy(feeText = seeded.feeText))
        val category = seeded.withUpdate(TransferCorrectionFieldUpdate.FeeCategory(foodCategoryId))
        assertEquals(foodCategoryId, category.feeCategoryId)
        assertEquals(seeded, category.copy(feeCategoryId = seeded.feeCategoryId))
    }

    @Test
    fun theFeeCategoryOptionsAreTheActiveLevelTwoExpenseCategories() {
        val parent = CategoryTreeView(CategoryId("category-parent"), parentId = null, name = "餐饮", kind = CategoryKind.EXPENSE, active = true, postingAccountId = null)
        val activeLeaf =
            CategoryTreeView(
                CategoryId("category-leaf"),
                parentId = CategoryId("category-parent"),
                name = "早餐",
                kind = CategoryKind.EXPENSE,
                active = true,
                postingAccountId = AccountId("expense-breakfast"),
            )
        val inactiveLeaf = activeLeaf.copy(categoryId = CategoryId("category-inactive"), name = "外卖", active = false)
        val incomeLeaf = activeLeaf.copy(categoryId = CategoryId("category-income"), name = "工资", kind = CategoryKind.INCOME)
        val options = transferFeeCategoryOptions(snapshot(categories = listOf(parent, activeLeaf, inactiveLeaf, incomeLeaf)))
        // Only the active level-two EXPENSE category survives: a positive fee must pick a category
        // the domain admits, and the top-level parent, the deactivated leaf and the income leaf are
        // all inadmissible there.
        assertEquals(listOf(CategoryId("category-leaf") to "早餐"), options)
        // The default fixture holds only top-level categories, so no fee category is offerable.
        assertEquals(emptyList<Pair<CategoryId, String>>(), transferFeeCategoryOptions(snapshot()))
    }

    @Test
    fun theStatisticsSeamKeepsTheStoredInstantUntilTheTextIsEditedThenFailsClosed() {
        val origin = resolvedOrigin()
        val seeded = seedDraft(origin)
        // Untouched text: the ORIGIN instant verbatim, and the parser is never consulted - a stored
        // instant's sub-second precision must survive a round trip through the display text.
        var parseCalls = 0
        val neverParses: (String) -> Instant? = {
            parseCalls += 1
            null
        }
        assertEquals(origin.statisticsAt, transferCorrectionStatisticsAt(origin, seeded, neverParses))
        assertEquals(0, parseCalls)
        // Edited text goes to the parser exactly once, with the text as typed.
        val edited = seeded.withUpdate(TransferCorrectionFieldUpdate.StatisticsAt("2026-04-01T00:00:00Z"))
        val seen = mutableListOf<String>()
        val parses: (String) -> Instant? = {
            seen += it
            Instant.parse(it)
        }
        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), transferCorrectionStatisticsAt(origin, edited, parses))
        assertEquals(listOf("2026-04-01T00:00:00Z"), seen)
        // An unreadable edit is null, never a silent fall back to the stored instant.
        assertNull(transferCorrectionStatisticsAt(origin, edited) { null })
    }
}
