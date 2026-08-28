package com.unifiedledger.domain

data class TransactionNoteUpdateCommand(
    val note: String,
)

data class TransactionNoteUpdateIds(
    val versionId: TransactionVersionId,
)

/**
 * RG-01 note_update. Copies the current version with `version_number + 1`, changes only
 * the note and reuses the same posting set. Since D-085 the shared implementation is the
 * [FormalTransaction.appendVersion] primitive with [TransactionVersionChange.Note]; this
 * wrapper keeps the RG-01 call semantics (and its behavior) unchanged.
 */
fun FormalTransaction.replaceNote(
    command: TransactionNoteUpdateCommand,
    ids: TransactionNoteUpdateIds,
): DomainResult<FormalTransaction> =
    appendVersion(
        change = TransactionVersionChange.Note(command.note),
        ids = TransactionVersionAppendIds(versionId = ids.versionId),
        newPostingSetId = null,
    )
