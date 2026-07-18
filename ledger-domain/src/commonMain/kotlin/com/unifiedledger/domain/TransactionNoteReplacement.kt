package com.unifiedledger.domain

data class TransactionNoteUpdateCommand(
    val note: String,
)

data class TransactionNoteUpdateIds(
    val versionId: TransactionVersionId,
)

fun FormalTransaction.replaceNote(
    command: TransactionNoteUpdateCommand,
    ids: TransactionNoteUpdateIds,
): DomainResult<FormalTransaction> {
    if (versions.any { it.id == ids.versionId }) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    val currentVersion = versions.single { it.id == transaction.currentVersionId }
    if (currentVersion.versionNumber == Int.MAX_VALUE) {
        return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
    }

    val replacementVersion = currentVersion.copy(
        id = ids.versionId,
        versionNumber = currentVersion.versionNumber + 1,
        note = command.note,
    )
    return FormalTransaction.create(
        transaction = transaction.copy(currentVersionId = ids.versionId),
        versions = versions + replacementVersion,
        postingSets = postingSets,
    )
}
