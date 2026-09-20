package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryCorrectionCommand
import com.unifiedledger.domain.OrdinaryCorrectionPlan
import com.unifiedledger.domain.OrdinaryCorrectionPostingIds
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.planOrdinaryCorrectionPostings
import kotlin.time.Instant

/**
 * P7-05.B product version correction (spec section 3.2; D-156).
 *
 * The request carries the complete target state of the new version, never a delta, so an
 * equivalent replay can be compared column by column. The flow is read current version →
 * preview (pure read, zero writes) → explicit confirmation → a fresh request id → claim-first
 * atomic commit guarded by the `expectedCurrentVersionId` CAS → authoritative re-read.
 * `occurredAt` is deliberately not part of the field set (DP-7 keeps it OPEN).
 */
data class ExplicitlyConfirmedTransactionCorrection(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val transactionId: TransactionId,
    val expectedCurrentVersionId: TransactionVersionId,
    val note: String?,
    val statisticsAt: Instant,
    val amount: Money,
    val categoryId: CategoryId,
    val fundingAccountId: AccountId,
    val confirmation: ExplicitManualSave,
)

data class TransactionCorrectionRequestIdentity(
    val ledgerId: LedgerId,
    val requestId: RequestId,
)

/** The frozen snapshot column set (spec section 4.3); `confirmation_marker` is the constant. */
data class TransactionCorrectionRequestSnapshot(
    val ledgerId: LedgerId,
    val transactionId: TransactionId,
    val expectedCurrentVersionId: TransactionVersionId,
    val note: String?,
    val statisticsAt: Instant,
    val amount: Money,
    val categoryId: CategoryId,
    val fundingAccountId: AccountId,
)

data class TransactionCorrectionReceipt(
    val confirmationId: ConfirmationId,
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val expectedCurrentVersionId: TransactionVersionId,
)

/** Fresh ids of one correction: the receipt identity plus the replacement posting set. */
data class CorrectTransactionVersionIds(
    val confirmationId: ConfirmationId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val categoryPostingId: PostingId,
    val fundingPostingId: PostingId,
)

fun interface CorrectTransactionVersionIdSource {
    fun next(): CorrectTransactionVersionIds
}

/** The persisted facts of the correction target, read inside the write transaction. */
data class TransactionCorrectionTarget(
    val transactionId: TransactionId,
    val kind: TransactionKind,
    val currentVersionId: TransactionVersionId,
    /** The current version's postings, so the plan can pick the frozen write form by field diff. */
    val postings: List<Posting>,
)

/**
 * The plan callback's outcome. [Commit] carries the freshly derived posting set, so the
 * catalog revalidation that produced it runs inside the commit transaction (spec section 3.2
 * "校验"); [Rejected] carries the frozen failure code and writes nothing.
 *
 * [reuseCurrentPostingSet] is the frozen write form (spec section 3.2 "写形"): when the
 * `(accountId, amount, currency)` leg set is unchanged — a note-only or statistics-only
 * correction — the appended version keeps the current posting set, so posting identity is not
 * rebound and the basis of "unchanged funding legs keep their reconciliation rows and evidence
 * links" holds. Only a real leg change allocates [postingSetId].
 */
sealed interface TransactionCorrectionPlan {
    data class Commit(
        val receipt: TransactionCorrectionReceipt,
        val postingSetId: PostingSetId,
        val postings: List<Posting>,
        val reuseCurrentPostingSet: Boolean,
    ) : TransactionCorrectionPlan

    data class Rejected(
        val code: P705FailureCode,
    ) : TransactionCorrectionPlan
}

/**
 * Whether the correction keeps the current posting set: true exactly when the new leg set has
 * the same `(accountId, amount, currency)` multiset as the current one. Posting ids and leg
 * order are deliberately not part of the comparison — they are identity, not economic content,
 * and a reordered or re-identified set of the same legs is still the unchanged set the spec
 * section 3.2 write form keeps.
 */
fun reuseCurrentPostingSet(
    currentPostings: List<Posting>,
    newPostings: List<Posting>,
): Boolean = currentPostings.legMultiset() == newPostings.legMultiset()

private fun List<Posting>.legMultiset(): Map<Triple<AccountId, Long, CurrencyUnit>, Int> = groupingBy { Triple(it.accountId, it.amount.minorUnits, it.amount.currency) }.eachCount()

/**
 * Spec section 3.2 freezes "备注长度沿既有上限": the correction path reuses the P7-02 entry note
 * bound ([ENTRY_NOTE_MAX_CODE_POINTS]) instead of storing the note verbatim. An over-long note
 * is an inadmissible field value and is reported through the frozen field code — the frozen
 * table has no note-specific token, the same batch-ruled reading the amount cases use.
 */
internal fun correctionNoteRejection(note: String?): P705FailureCode? = if (note != null && validateEntryNote(note) != null) P705FailureCode.P705_FIELD_NOT_SUPPORTED else null

/**
 * Result family of the correction use case: the four states of the existing confirmation
 * surfaces plus [StaleCurrentVersion] as its own variant (never folded into [Rejected],
 * spec section 3.2 "结果面冻结").
 */
sealed interface CorrectTransactionVersionResult {
    data class Created(
        val receipt: TransactionCorrectionReceipt,
    ) : CorrectTransactionVersionResult

    data class NoChange(
        val receipt: TransactionCorrectionReceipt,
    ) : CorrectTransactionVersionResult

    data class RequestIdentityConflict(
        val identity: TransactionCorrectionRequestIdentity,
    ) : CorrectTransactionVersionResult

    data object StaleCurrentVersion : CorrectTransactionVersionResult

    data class Rejected(
        val code: P705FailureCode,
    ) : CorrectTransactionVersionResult
}

/**
 * Claim-first, request-idempotent commit boundary. Replay resolution happens before any CAS
 * evaluation: when the claim is not won the port compares the stored snapshot and returns
 * [CorrectTransactionVersionResult.NoChange] or
 * [CorrectTransactionVersionResult.RequestIdentityConflict] without ever reading the current
 * version, so a post-success replay that carries a newly read CAS token is an identity
 * conflict and never a false stale (spec sections 3.2/4.3).
 */
fun interface CorrectTransactionVersionCommitPort {
    fun commitOnce(
        identity: TransactionCorrectionRequestIdentity,
        requestSnapshot: TransactionCorrectionRequestSnapshot,
        plan: (TransactionCorrectionTarget) -> TransactionCorrectionPlan,
    ): CorrectTransactionVersionResult
}

/**
 * The product correction use case. The plan lambda runs inside the commit transaction, so the
 * injected [admissionReader] observes the same authoritative catalog the write commits against
 * (spec section 3.2 "校验"); a preview or an option snapshot is never commit permission.
 */
class ExecuteCorrectTransactionVersion(
    private val commitPort: CorrectTransactionVersionCommitPort,
    private val idSource: CorrectTransactionVersionIdSource,
    private val admissionReader: CatalogAdmissionReader,
) {
    fun execute(request: ExplicitlyConfirmedTransactionCorrection): CorrectTransactionVersionResult {
        val identity = TransactionCorrectionRequestIdentity(request.ledgerId, request.requestId)
        val snapshot =
            TransactionCorrectionRequestSnapshot(
                ledgerId = request.ledgerId,
                transactionId = request.transactionId,
                expectedCurrentVersionId = request.expectedCurrentVersionId,
                note = request.note,
                statisticsAt = request.statisticsAt,
                amount = request.amount,
                categoryId = request.categoryId,
                fundingAccountId = request.fundingAccountId,
            )
        return commitPort.commitOnce(identity, snapshot) { target -> planCorrection(target, snapshot) }
    }

    private fun planCorrection(
        target: TransactionCorrectionTarget,
        snapshot: TransactionCorrectionRequestSnapshot,
    ): TransactionCorrectionPlan {
        // The note bound is part of the frozen field validation (spec section 3.2), so an
        // over-long note is rejected before any id is minted or any row is written.
        correctionNoteRejection(snapshot.note)?.let { return TransactionCorrectionPlan.Rejected(it) }
        val ids = idSource.next()
        val receipt =
            TransactionCorrectionReceipt(
                confirmationId = ids.confirmationId,
                transactionId = target.transactionId,
                versionId = ids.versionId,
                expectedCurrentVersionId = snapshot.expectedCurrentVersionId,
            )
        val catalog =
            admissionReader.loadCurrent(snapshot.ledgerId)
                ?: return TransactionCorrectionPlan.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE)
        val command =
            OrdinaryCorrectionCommand(
                ledgerId = snapshot.ledgerId,
                kind = target.kind,
                amount = snapshot.amount,
                categoryId = snapshot.categoryId,
                fundingAccountId = snapshot.fundingAccountId,
            )
        return when (
            val planned =
                planOrdinaryCorrectionPostings(
                    catalog = catalog,
                    command = command,
                    ids =
                        OrdinaryCorrectionPostingIds(
                            categoryPostingId = ids.categoryPostingId,
                            fundingPostingId = ids.fundingPostingId,
                        ),
                )
        ) {
            is OrdinaryCorrectionPlan.Rejected -> TransactionCorrectionPlan.Rejected(planned.code)
            is OrdinaryCorrectionPlan.Postings ->
                TransactionCorrectionPlan.Commit(
                    receipt = receipt,
                    postingSetId = ids.postingSetId,
                    postings = planned.postings,
                    reuseCurrentPostingSet = reuseCurrentPostingSet(target.postings, planned.postings),
                )
        }
    }
}
