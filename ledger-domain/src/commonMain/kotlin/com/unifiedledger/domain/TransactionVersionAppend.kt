package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * D-085 RG-11/12 shared "append next version" domain primitive. Generalizes the RG-01
 * note_update replacement ([FormalTransaction.replaceNote]) into a single append-version
 * primitive over three change forms:
 *
 * - [TransactionVersionChange.Note] — RG-01 note_update: change only the note.
 * - [TransactionVersionChange.StatisticsAt] — RG-11 `correct_transaction_version`
 *   `statistics_time` semantics: change only the statistics time; the caller pairs this
 *   with [createExplicitOperationConfirmation] (delivered with shard 1a) for the
 *   `explicit_operation_confirmation` reference of the frozen main-correct fixture.
 * - [TransactionVersionChange.Postings] — RG-12 `correct_transaction_version`
 *   `posting_facts` semantics (in use since RG-12): full replacement postings bound through
 *   [FormalTransaction.appendVersion]'s `newPostingSetId`.
 *
 * Every form copies the current version with `version_number + 1`, applies only the
 * changed field, and keeps all previous versions and posting sets untouched. When
 * `newPostingSetId` is null the current posting set is reused (note_update and
 * statistics_time); when non-null a fresh, validated posting set is created and bound
 * (RG-12 posting_facts).
 */
sealed interface TransactionVersionChange {
    data class Note(val note: String) : TransactionVersionChange

    data class StatisticsAt(val statisticsAt: Instant) : TransactionVersionChange

    data class Postings(val postings: List<Posting>) : TransactionVersionChange
}

data class TransactionVersionAppendIds(
    val versionId: TransactionVersionId,
)

fun FormalTransaction.appendVersion(
    change: TransactionVersionChange,
    ids: TransactionVersionAppendIds,
    newPostingSetId: PostingSetId? = null,
): DomainResult<FormalTransaction> {
    if (versions.any { it.id == ids.versionId }) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    val currentVersion = versions.single { it.id == transaction.currentVersionId }
    if (currentVersion.versionNumber == Int.MAX_VALUE) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    val appendedPostingSetId: PostingSetId
    val appendedPostingSets: List<PostingSet>
    when (change) {
        is TransactionVersionChange.Note,
        is TransactionVersionChange.StatisticsAt,
        -> {
            if (newPostingSetId != null) {
                // A fresh posting set is only meaningful with the Postings form.
                return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
            }
            appendedPostingSetId = currentVersion.postingSetId
            appendedPostingSets = postingSets
        }

        is TransactionVersionChange.Postings -> {
            val freshSetId = newPostingSetId
                ?: return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
            when (val created = PostingSet.create(freshSetId, change.postings)) {
                is DomainResult.Failure -> return created
                is DomainResult.Success -> {
                    appendedPostingSetId = freshSetId
                    appendedPostingSets = postingSets + created.value
                }
            }
        }
    }

    val replacementVersion = currentVersion.copy(
        id = ids.versionId,
        versionNumber = currentVersion.versionNumber + 1,
        postingSetId = appendedPostingSetId,
        times = when (change) {
            is TransactionVersionChange.StatisticsAt ->
                currentVersion.times.copy(statisticsAt = change.statisticsAt)
            else -> currentVersion.times
        },
        note = when (change) {
            is TransactionVersionChange.Note -> change.note
            else -> currentVersion.note
        },
    )

    return FormalTransaction.create(
        transaction = transaction.copy(currentVersionId = ids.versionId),
        versions = versions + replacementVersion,
        postingSets = appendedPostingSets,
    )
}
