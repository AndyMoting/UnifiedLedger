package com.unifiedledger.ui

import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.VoidReasonCode
import kotlin.time.Instant

/**
 * P7-05.B (D-156; spec section 4.4) correction surface support types.
 *
 * The correction form is an **independent** surface: it deliberately does not reuse the P7-02
 * `TypedEntryDraft`/field-retention/typing-retention discipline (spec section 4.4), so the
 * existing entry face keeps its frozen semantics byte-for-byte. The draft carries exactly the
 * frozen first-slice field set (note / `statisticsAt` / amount / category / funding account,
 * spec section 3.2); `occurredAt` is deliberately absent (DP-7 keeps it OPEN).
 */
data class TransactionCorrectionDraft(
    val note: String = "",
    val statisticsAtText: String = "",
    val amountText: String = "",
    val categoryId: CategoryId? = null,
    val fundingAccountId: AccountId? = null,
)

/** The typed `UpdateTransactionCorrectionField(update)` payload (spec section 4.4). */
sealed interface TransactionCorrectionFieldUpdate {
    data class Note(
        val text: String,
    ) : TransactionCorrectionFieldUpdate

    data class StatisticsAt(
        val text: String,
    ) : TransactionCorrectionFieldUpdate

    data class Amount(
        val text: String,
    ) : TransactionCorrectionFieldUpdate

    data class Category(
        val categoryId: CategoryId,
    ) : TransactionCorrectionFieldUpdate

    data class FundingAccount(
        val accountId: AccountId,
    ) : TransactionCorrectionFieldUpdate
}

/** Pure draft write; preserves the concrete draft shape (the `ImportDecisionDraft` precedent). */
fun TransactionCorrectionDraft.withUpdate(update: TransactionCorrectionFieldUpdate): TransactionCorrectionDraft =
    when (update) {
        is TransactionCorrectionFieldUpdate.Note -> copy(note = update.text)
        is TransactionCorrectionFieldUpdate.StatisticsAt -> copy(statisticsAtText = update.text)
        is TransactionCorrectionFieldUpdate.Amount -> copy(amountText = update.text)
        is TransactionCorrectionFieldUpdate.Category -> copy(categoryId = update.categoryId)
        is TransactionCorrectionFieldUpdate.FundingAccount -> copy(fundingAccountId = update.accountId)
    }

/**
 * P7-05.B: seeds the correction form with the target's current values, so a freshly opened form
 * shows no difference and the user only changes what they mean to change. The host passes the
 * origin on [P503UiEvent.OpenTransactionEdit]; the reducer stays IO-free and derives the seed
 * from that same payload.
 */
fun transactionCorrectionDraftFromOrigin(origin: TransactionEditOrigin): TransactionCorrectionDraft =
    TransactionCorrectionDraft(
        note = origin.note.orEmpty(),
        statisticsAtText = origin.statisticsAt.toString(),
        amountText = origin.amountText,
        categoryId = origin.categoryId,
        fundingAccountId = origin.fundingAccountId,
    )

/**
 * P7-05.B (spec sections 3.2/4.4): the host-resolved old-value snapshot of the correction target.
 * Resolved by the host from the authoritative current-version detail plus the current catalog
 * (the reducer never reads a catalog), it carries the CAS token the commit must send
 * (`currentVersionId`) together with the old display values the difference preview shows.
 */
data class TransactionEditOrigin(
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val note: String?,
    val statisticsAt: Instant,
    val amountText: String,
    val categoryId: CategoryId?,
    val fundingAccountId: AccountId?,
)

/** The corrected fields of the frozen first-slice set, in the preview's display order. */
enum class TransactionEditField {
    NOTE,
    STATISTICS_AT,
    AMOUNT,
    CATEGORY,
    FUNDING_ACCOUNT,
}

/** One difference-preview line: the old and the new display text of one corrected field. */
data class TransactionEditFieldDiff(
    val field: TransactionEditField,
    val oldText: String,
    val newText: String,
) {
    val changed: Boolean get() = oldText != newText
}

/**
 * P7-05.B (spec section 4.4): the pure field-by-field difference preview. It is a display-only
 * read — a preview is never a commit permission (spec section 3.2): only an explicit
 * `ConfirmTransactionEdit` allocates the intent, and only the `expectedCurrentVersionId` CAS can
 * accept it.
 */
data class TransactionEditPreview(
    val fields: List<TransactionEditFieldDiff>,
)

/**
 * P7-05.C (DP-11; spec section 3.3): the void/restore reason form draft. The reason is mandatory
 * and its domain representation is the single [com.unifiedledger.domain.VoidReason] type (typed
 * code + optional bounded note); the draft only holds the typed text until confirmation.
 */
data class VoidReasonDraft(
    val code: VoidReasonCode? = null,
    val note: String = "",
)

/** The typed void/restore reason field write (DP-11). */
sealed interface VoidReasonFieldUpdate {
    data class Code(
        val code: VoidReasonCode,
    ) : VoidReasonFieldUpdate

    data class Note(
        val text: String,
    ) : VoidReasonFieldUpdate
}

/** Pure reason-draft write. */
fun VoidReasonDraft.withUpdate(update: VoidReasonFieldUpdate): VoidReasonDraft =
    when (update) {
        is VoidReasonFieldUpdate.Code -> copy(code = update.code)
        is VoidReasonFieldUpdate.Note -> copy(note = update.text)
    }

/**
 * P7-05 (spec section 4.2/4.4): the typed outcome banner of a P7-05 operation surface. The
 * `StaleCurrentVersion` and `RequestIdentityConflict` outcomes are their own variants and are
 * never folded into [Rejected] (spec section 3.2 "结果面冻结"). A lost commit is not a notice:
 * it leaves the surface in its submitting marker (the `UnknownCommit` discipline — no auto-retry,
 * no requestId swap, no leaving), so no "unknown" banner is fabricated.
 */
sealed interface P705Notice {
    data class Rejected(
        val code: P705FailureCode,
    ) : P705Notice

    data object StaleCurrentVersion : P705Notice

    data class RequestIdentityConflict(
        val requestId: RequestId,
    ) : P705Notice
}

/**
 * P7-05.C (DP-8/DP-11; spec section 4.4): the nested restore-confirmation sub-state of the
 * recycle bin. Entered from one bin row, it mirrors the void confirmation's shape — mandatory
 * typed reason and per-operation submitting marker — while living inside
 * [P503AppState.RecycleBin] rather than as its own top-level state. The restore request carries no
 * CAS token: the merged void/restore family is guarded by the fact sequence (DP-8), not by a
 * version CAS.
 */
data class RestoreConfirm(
    val transactionId: TransactionId,
    val reason: VoidReasonDraft = VoidReasonDraft(),
    val requestId: RequestId? = null,
    val submitting: Boolean = false,
    val notice: P705Notice? = null,
)
