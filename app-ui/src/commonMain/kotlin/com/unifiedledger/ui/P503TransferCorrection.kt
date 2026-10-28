package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.parseExactDecimal
import com.unifiedledger.domain.parseExactDecimalLenient
import kotlin.time.Instant

// P7-05 transfer correction slice (D-156, D-228): the app-ui PURE layer of the transfer correction
// surface. The frozen contract lives in
// docs/specs/2026-10-16-p7-05-transfer-correction-slice-design.md - section 3 (field set, write
// shape, validation) and section 8.3 (request row shape: the destination credit rides the amount
// column, the source account rides funding_account_id, the fee category rides category_id and is
// NULL exactly when the fee is 0).
//
// This file holds no Compose, no IO, no logging, no platform API and no coroutines. Amounts are Long
// minor units end to end - never Double or Float.
//
// The pure layer is NOT the whole slice: the transfer void/restore face and the Android manual
// acceptance are still open, so nothing here signs off 05.D. The snapshot-aware resolver on the read
// side already reconstructs both shapes column for column (the TransactionCorrectionRequestSnapshot
// contract in CorrectTransactionVersion.kt).

/**
 * P7-05 transfer slice (spec section 3): the host-resolved old-value snapshot of a manual transfer
 * correction target.
 *
 * [destinationCreditMinorUnits] is the frozen amount column - the source debit is derived
 * (`sourceDebit = destinationCredit + fee`, spec section 8.3/`ACCOUNTING_RULES.md:275`) and is never
 * stored on its own. [feeCategoryId] is the fee category of a fee-bearing transfer and `null`
 * exactly when [feeMinorUnits] is `0` (spec section 8.3; `ACCOUNTING_RULES.md:273`). [currency] is
 * the transaction's own currency, resolved from the destination leg.
 */
data class TransferCorrectionOrigin(
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val note: String?,
    val statisticsAt: Instant,
    val currency: CurrencyUnit,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val destinationCreditMinorUnits: Long,
    val feeMinorUnits: Long,
    val feeCategoryId: CategoryId?,
)

/**
 * The transfer correction form draft: the frozen seven fields, four of them as typed text ([note],
 * [statisticsAtText], [destinationCreditText], [feeText]) and three as resolved references
 * ([sourceAccountId], [destinationAccountId], [feeCategoryId]). Seeded from [TransferCorrectionOrigin]
 * so a freshly opened form previews no difference.
 *
 * [statisticsAtText] is display text only - [transferCorrectionRequest] never parses it (see its
 * contract). The remaining texts are read by the preview on every keystroke and by the builder only
 * at confirmation.
 */
data class TransferCorrectionDraft(
    val note: String = "",
    val statisticsAtText: String = "",
    val destinationCreditText: String = "",
    val sourceAccountId: AccountId? = null,
    val destinationAccountId: AccountId? = null,
    val feeText: String = "",
    val feeCategoryId: CategoryId? = null,
)

/**
 * The typed transfer-form field write (spec section 3): the frozen seven-field set, one field per
 * write. The four text fields carry the raw text - the preview reads them on every keystroke and the
 * request builder only at confirmation; the three reference fields carry already-resolved ids
 * because the reducer is IO-free and never reads the catalog.
 */
sealed interface TransferCorrectionFieldUpdate {
    data class Note(
        val text: String,
    ) : TransferCorrectionFieldUpdate

    data class StatisticsAt(
        val text: String,
    ) : TransferCorrectionFieldUpdate

    data class DestinationCredit(
        val text: String,
    ) : TransferCorrectionFieldUpdate

    data class SourceAccount(
        val accountId: AccountId,
    ) : TransferCorrectionFieldUpdate

    data class DestinationAccount(
        val accountId: AccountId,
    ) : TransferCorrectionFieldUpdate

    data class Fee(
        val text: String,
    ) : TransferCorrectionFieldUpdate

    data class FeeCategory(
        val categoryId: CategoryId,
    ) : TransferCorrectionFieldUpdate
}

/**
 * Pure draft write over the seven frozen fields; preserves the concrete [TransferCorrectionDraft]
 * shape (the [TransactionCorrectionDraft.withUpdate] precedent).
 */
internal fun TransferCorrectionDraft.withUpdate(update: TransferCorrectionFieldUpdate): TransferCorrectionDraft =
    when (update) {
        is TransferCorrectionFieldUpdate.Note -> copy(note = update.text)
        is TransferCorrectionFieldUpdate.StatisticsAt -> copy(statisticsAtText = update.text)
        is TransferCorrectionFieldUpdate.DestinationCredit -> copy(destinationCreditText = update.text)
        is TransferCorrectionFieldUpdate.SourceAccount -> copy(sourceAccountId = update.accountId)
        is TransferCorrectionFieldUpdate.DestinationAccount -> copy(destinationAccountId = update.accountId)
        is TransferCorrectionFieldUpdate.Fee -> copy(feeText = update.text)
        is TransferCorrectionFieldUpdate.FeeCategory -> copy(feeCategoryId = update.categoryId)
    }

/**
 * The transfer slice's own corrected-field set, in the preview's display order (frozen; spec section
 * 3 field set: note, statistics time, destination credit, source account, destination account, fee,
 * fee category).
 *
 * It is deliberately a SEPARATE enum from [TransactionEditField]: `P503CorrectionReducerTest.kt:194`
 * asserts the ordinary enum matches the ordinary preview one-to-one, and the ordinary
 * expense/income face stays byte-for-byte unchanged. Reordering these entries reorders the preview.
 */
internal enum class TransferEditField {
    NOTE,
    STATISTICS_AT,
    DESTINATION_CREDIT,
    SOURCE_ACCOUNT,
    DESTINATION_ACCOUNT,
    FEE,
    FEE_CATEGORY,
}

/** One rendered difference-preview line of the transfer surface: its field, label and display texts. */
internal data class TransferEditDiffLine(
    val field: TransferEditField,
    val label: String,
    val oldText: String,
    val newText: String,
) {
    val changed: Boolean get() = oldText != newText
}

/**
 * The transfer difference preview: the seven [TransferEditField] entries in display order, already
 * rendered to display text. Display only - a preview is never a commit permission (spec section 3).
 */
internal data class TransferEditPreview(
    val fields: List<TransferEditDiffLine>,
)

/**
 * The display label of one transfer field, in the spec section 3 field-set order. That section
 * freezes the field set and its order; these label texts are this surface's own.
 */
internal fun transferEditFieldLabel(field: TransferEditField): String =
    when (field) {
        TransferEditField.NOTE -> "备注"
        TransferEditField.STATISTICS_AT -> "统计时间"
        TransferEditField.DESTINATION_CREDIT -> "到账金额"
        TransferEditField.SOURCE_ACCOUNT -> "源账户"
        TransferEditField.DESTINATION_ACCOUNT -> "目标账户"
        TransferEditField.FEE -> "手续费"
        TransferEditField.FEE_CATEGORY -> "手续费分类"
    }

/**
 * P7-05 transfer slice (spec section 3): resolves the host-side origin of a transfer correction from
 * the authoritative detail plus the current catalog.
 *
 * The leg SHAPE is the discriminator: this resolver does not filter by kind, creation lineage or the
 * lending wall, so an `ACCOUNT_TRANSFER`-shaped detail resolves identically however it was created
 * (manual or imported) and so does a principal transfer between owned assets. Whether such a row gets
 * an edit entrance, and whether it must be rejected typed, is a wiring/domain decision outside this
 * slice - the shape rule here is the frozen one.
 *
 * Rules (frozen):
 * - the two accepted shapes are the domain's own transfer postings:
 *   `ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/AccountTransfer.kt:187-202` builds
 *   PRINCIPAL_OUT, PRINCIPAL_IN and the FEE leg that carries the fee category, and
 *   `ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/OwnAssetPrincipalTransfer.kt:94-104`
 *   builds PRINCIPAL_OUT and PRINCIPAL_IN alone;
 * - the source leg is the single NEGATIVE leg whose account is a manageable `ASSET` account of the
 *   snapshot; the destination leg is the single POSITIVE one;
 * - only for a three-leg shape, the fee leg is the remaining POSITIVE leg whose account is the
 *   `postingAccountId` of EXACTLY ONE snapshot category - that category is the fee category; a
 *   two-leg transfer resolves `feeMinorUnits = 0` and `feeCategoryId = null`;
 * - the shape must be unambiguous: exactly two or exactly three legs, and exactly two guards hold
 *   source and destination apart - the posting ids are pairwise distinct, and the two accounts
 *   differ. Every leg carries the destination leg's currency;
 * - the lending wall and the hidden accounts are excluded by two account-membership tests only: the
 *   leg's account must sit inside the `CatalogSnapshotView.manageableAccounts` projection
 *   (`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/CatalogProjection.kt:47-56`
 *   keeps the `isManageableAccount` accounts - `ASSET` and `LIABILITY` alike,
 *   `ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/CatalogManagement.kt:152-160` -
 *   and drops the system-role, non-real and unowned ones), and then must be `ASSET` here, because a
 *   transfer endpoint admits `ASSET` only (`AccountTransfer.kt:80`, `:135`, `:140`).
 *
 * Anything else resolves `null`, so the caller offers no edit entrance instead of guessing: no
 * `firstOrNull` winner, no silent pick among two candidates.
 */
internal fun transferCorrectionOriginFromDetail(
    detail: TransactionDetail,
    snapshot: CatalogSnapshotView,
): TransferCorrectionOrigin? {
    val legs = detail.legs
    if (legs.size != 2 && legs.size != 3) return null
    if (legs.map { it.postingId }.toSet().size != legs.size) return null
    val assetAccountIds =
        snapshot
            .manageableAccounts
            .filter { it.kind == AccountKind.ASSET }
            .map { it.accountId }
            .toSet()
    val assetLegs = legs.filter { it.accountId in assetAccountIds }
    val sourceLeg = assetLegs.singleOrNull { it.amount.minorUnits < 0L } ?: return null
    val destinationLeg = assetLegs.singleOrNull { it.amount.minorUnits > 0L } ?: return null
    if (sourceLeg.accountId == destinationLeg.accountId) return null
    val currency = destinationLeg.amount.currency
    if (sourceLeg.amount.currency != currency) return null
    val feeLeg = legs.singleOrNull { it.postingId != sourceLeg.postingId && it.postingId != destinationLeg.postingId }
    val feeCategoryId: CategoryId?
    val feeMinorUnits: Long
    if (feeLeg == null) {
        feeMinorUnits = 0L
        feeCategoryId = null
    } else {
        if (feeLeg.amount.minorUnits <= 0L) return null
        if (feeLeg.amount.currency != currency) return null
        val feeCategory = snapshot.categories.filter { it.postingAccountId == feeLeg.accountId }.singleOrNull() ?: return null
        feeMinorUnits = feeLeg.amount.minorUnits
        feeCategoryId = feeCategory.categoryId
    }
    return TransferCorrectionOrigin(
        transactionId = detail.transactionId,
        currentVersionId = detail.currentVersionId,
        note = detail.note,
        statisticsAt = detail.statisticsAt,
        currency = currency,
        sourceAccountId = sourceLeg.accountId,
        destinationAccountId = destinationLeg.accountId,
        destinationCreditMinorUnits = destinationLeg.amount.minorUnits,
        feeMinorUnits = feeMinorUnits,
        feeCategoryId = feeCategoryId,
    )
}

/** Seeds the transfer form from the origin, so an untouched form previews no difference. */
internal fun transferCorrectionDraftFromOrigin(origin: TransferCorrectionOrigin): TransferCorrectionDraft =
    TransferCorrectionDraft(
        note = origin.note.orEmpty(),
        statisticsAtText = origin.statisticsAt.toString(),
        destinationCreditText = formatMinorUnits(origin.destinationCreditMinorUnits, origin.currency.precision),
        sourceAccountId = origin.sourceAccountId,
        destinationAccountId = origin.destinationAccountId,
        feeText = formatMinorUnits(origin.feeMinorUnits, origin.currency.precision),
        feeCategoryId = origin.feeCategoryId,
    )

/**
 * P7-05 transfer slice (spec section 3): the pure seven-line difference preview. The field order IS
 * the display order: the rows follow the frozen [TransferEditField] entry order exactly.
 *
 * Each side renders display text: the note shows 无 when empty (a whitespace-only note is real text);
 * an account/category reference shows its CURRENT catalog name, else its stable id, else 无; the two
 * amounts and the fee show the formatted exact value with their sign and currency code; the
 * statistics time shows both time-zone semantics.
 *
 * The NEW fee category is derived from the target fee ([transferPreviewFeeCategoryId]) - never taken
 * straight off the residual draft category - so a fee driven to zero shows 无, matching the request
 * the builder would produce.
 *
 * This is a display-only read and it is NOT an admission decision: a text the precision cannot parse
 * is echoed verbatim while the builder rejects it, and a text that formats can still be inadmissible
 * (a zero or negative amount). Admission is the five independent `null` branches of
 * [transferCorrectionRequest]; formatting success is never admission.
 */
internal fun transferCorrectionPreview(
    origin: TransferCorrectionOrigin,
    draft: TransferCorrectionDraft,
    categoryNames: Map<CategoryId, String>,
    accountNames: Map<AccountId, String>,
): TransferEditPreview {
    val currency = origin.currency
    val precision = currency.precision
    val targetFeeCategoryId = transferPreviewFeeCategoryId(draft.feeText, draft.feeCategoryId, currency)
    val fields =
        listOf(
            transferDiffLine(
                TransferEditField.NOTE,
                transferNoteDisplayText(origin.note),
                transferNoteDisplayText(draft.note),
            ),
            transferDiffLine(
                TransferEditField.STATISTICS_AT,
                correctionTimeDisplayText(origin.statisticsAt.toString()),
                correctionTimeDisplayText(draft.statisticsAtText),
            ),
            transferDiffLine(
                TransferEditField.DESTINATION_CREDIT,
                correctionAmountDisplayText(formatMinorUnits(origin.destinationCreditMinorUnits, precision), currency),
                correctionAmountDisplayText(draft.destinationCreditText, currency),
            ),
            transferDiffLine(
                TransferEditField.SOURCE_ACCOUNT,
                accountReferenceDisplayText(origin.sourceAccountId.value, accountNames),
                accountReferenceDisplayText(draft.sourceAccountId?.value.orEmpty(), accountNames),
            ),
            transferDiffLine(
                TransferEditField.DESTINATION_ACCOUNT,
                accountReferenceDisplayText(origin.destinationAccountId.value, accountNames),
                accountReferenceDisplayText(draft.destinationAccountId?.value.orEmpty(), accountNames),
            ),
            transferDiffLine(
                TransferEditField.FEE,
                correctionAmountDisplayText(formatMinorUnits(origin.feeMinorUnits, precision), currency),
                correctionAmountDisplayText(draft.feeText, currency),
            ),
            transferDiffLine(
                TransferEditField.FEE_CATEGORY,
                categoryReferenceDisplayText(origin.feeCategoryId?.value.orEmpty(), categoryNames),
                categoryReferenceDisplayText(targetFeeCategoryId?.value.orEmpty(), categoryNames),
            ),
        )
    return TransferEditPreview(fields = fields)
}

/**
 * The target-side fee category of the preview: derived from the target fee text at the transaction
 * currency's precision - an unparseable text or an exact zero yields `null` (无), a positive fee
 * yields [draftCategoryId]. A negative fee parses, so it keeps the draft category even though the
 * builder rejects it: the preview never hides a reference the user typed.
 */
internal fun transferPreviewFeeCategoryId(
    feeText: String,
    draftCategoryId: CategoryId?,
    currency: CurrencyUnit,
): CategoryId? {
    val feeMinorUnits = transferCorrectionMinorUnits(feeText, currency) ?: return null
    return if (feeMinorUnits == 0L) null else draftCategoryId
}

/**
 * P7-05 transfer slice (spec sections 3/8.3): builds the correction request from the surface's draft
 * and origin.
 *
 * It returns `null` in exactly these five cases and in NO others:
 * (a) the destination account is missing;
 * (b) the source account is missing;
 * (c) the destination credit text is not an exact positive amount at the transaction currency's
 *     precision;
 * (d) the fee text is not an exact non-negative amount at that precision (a negative fee included);
 * (e) the fee is positive and no fee category is selected.
 *
 * Blank text is UNPARSEABLE, not zero: (c) and (d) reject a blank or whitespace-only field instead of
 * silently reading `0` - fail-closed, never a quiet reinterpretation of the user's input.
 *
 * A zero fee drops any residual draft category: `categoryId` is `null` exactly when `feeMinor == 0`
 * (spec section 8.3), even when the draft still carries one. Every other target state is accepted
 * here - same-account, off-catalog accounts and a category id outside the catalog included - because
 * more admission rejection belongs to the DOMAIN layer's typed rejection. A sixth `null` branch would
 * turn a typed failure code into a silent no-op, so there is none.
 *
 * [statisticsAt] is supplied by the caller. This builder does NOT parse
 * [TransferCorrectionDraft.statisticsAtText]: the frozen branch set contains no statistics-time parse
 * failure, and falling back to the origin instant on a failed parse would silently discard the user's
 * edit. [transferCorrectionStatisticsAt] is that seam - it returns the origin instant while the field
 * is untouched (which keeps the stored instant's sub-second precision) and `null` for an edited text
 * it cannot read, which the caller routes through the existing typed rejection.
 *
 * A non-null request is still not an admission claim: the commit's CAS plus the domain validation are
 * the only acceptance, and the preview's formatting of the same text decides nothing.
 */
internal fun transferCorrectionRequest(
    ledgerId: LedgerId,
    requestId: RequestId,
    origin: TransferCorrectionOrigin,
    draft: TransferCorrectionDraft,
    statisticsAt: Instant,
): ExplicitlyConfirmedTransactionCorrection? {
    val destinationAccountId = draft.destinationAccountId ?: return null
    val sourceAccountId = draft.sourceAccountId ?: return null
    val destinationCreditMinorUnits = transferCorrectionMinorUnits(draft.destinationCreditText, origin.currency) ?: return null
    if (destinationCreditMinorUnits <= 0L) return null
    val feeMinorUnits = transferCorrectionMinorUnits(draft.feeText, origin.currency) ?: return null
    if (feeMinorUnits < 0L) return null
    val feeCategoryId = if (feeMinorUnits == 0L) null else (draft.feeCategoryId ?: return null)
    return ExplicitlyConfirmedTransactionCorrection(
        ledgerId = ledgerId,
        requestId = requestId,
        transactionId = origin.transactionId,
        expectedCurrentVersionId = origin.currentVersionId,
        // An emptied note is the absent representation, matching the preview's 无 reading.
        note = draft.note.ifEmpty { null },
        statisticsAt = statisticsAt,
        amount = Money.ofMinor(destinationCreditMinorUnits, origin.currency),
        categoryId = feeCategoryId,
        fundingAccountId = sourceAccountId,
        destinationAccountId = destinationAccountId,
        feeMinor = feeMinorUnits,
        confirmation = ExplicitManualSave,
    )
}

/**
 * The statistics-time seam of the transfer surface (spec section 3): the instant a correction request
 * carries.
 *
 * While the form still shows the origin's seeded text the ORIGIN instant comes back verbatim: the
 * stored instant may carry sub-second precision the entry parser deliberately does not accept, and
 * re-typing the seeded text must not lose it. Only a user-edited text reaches [parseStatisticsAt];
 * `null` means that edit is not a readable instant, and the caller must route it through the
 * surface's existing typed field rejection rather than fall back to the stored instant - a silent
 * fallback would discard the user's edit without telling anyone.
 */
internal fun transferCorrectionStatisticsAt(
    origin: TransferCorrectionOrigin,
    draft: TransferCorrectionDraft,
    parseStatisticsAt: (String) -> Instant?,
): Instant? =
    if (draft.statisticsAtText == origin.statisticsAt.toString()) {
        origin.statisticsAt
    } else {
        parseStatisticsAt(draft.statisticsAtText)
    }

/**
 * The transfer form's fee-category options (spec section 3): the active level-2 `EXPENSE`
 * categories, which is exactly the admission the domain enforces for a positive fee (its posting
 * account is the hidden EXPENSE account the domain validates); a zero fee carries no category at
 * all.
 *
 * This list is narrower than what [transferCorrectionOriginFromDetail] accepts as a fee category:
 * the resolver answers only `which category does this fee leg belong to` (any catalog entry whose
 * posting account is the leg's account), while admission - level-2, active, EXPENSE - belongs to the
 * domain and to this option list. Keeping them separate is deliberate: the resolver must still
 * describe a legacy row whose fee category was deactivated afterwards, so the form can show what the
 * row holds instead of hiding the target.
 */
internal fun transferFeeCategoryOptions(snapshot: CatalogSnapshotView): List<Pair<CategoryId, String>> =
    snapshot.categories
        .filter { it.parentId != null && it.active && it.kind == CategoryKind.EXPENSE }
        .map { it.categoryId to it.name }

/**
 * The transfer surface's amount reading: trim, treat blank as unparseable, then the strict parser and -
 * only if it rejects - the lenient one. These are the same two exact parsers the entry flow and the
 * ordinary preview use ([parseExactDecimal] then [parseExactDecimalLenient], the
 * `P503CorrectionPresentation.kt:166-171` pair): no rounding, no binary floating point, and the lenient
 * parser still refuses a non-zero excess fraction digit. `null` means "not an exact amount at this
 * precision".
 */
private fun transferCorrectionMinorUnits(
    text: String,
    currency: CurrencyUnit,
): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return parseExactDecimal(trimmed, currency.precision) ?: parseExactDecimalLenient(trimmed, currency.precision)
}

/** One preview row with its frozen label; the caller supplies the already-rendered display texts. */
private fun transferDiffLine(
    field: TransferEditField,
    oldText: String,
    newText: String,
): TransferEditDiffLine = TransferEditDiffLine(field = field, label = transferEditFieldLabel(field), oldText = oldText, newText = newText)

/**
 * The transfer surface's note reading: an absent or empty note displays 无. Only the empty string is
 * the absent representation - a whitespace-only note is real text and displays as typed (the builder
 * applies the same rule when it stores the note).
 */
private fun transferNoteDisplayText(note: String?): String = if (note.isNullOrEmpty()) "无" else note
