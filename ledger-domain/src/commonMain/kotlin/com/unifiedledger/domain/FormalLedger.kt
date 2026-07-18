package com.unifiedledger.domain

data class Posting(
    val id: PostingId,
    val accountId: AccountId,
    val amount: Money,
)

class PostingSet private constructor(
    val id: PostingSetId,
    postings: List<Posting>,
) {
    val postings: List<Posting> = postings.toList()

    companion object {
        fun create(
            id: PostingSetId,
            postings: List<Posting>,
        ): DomainResult<PostingSet> {
            if (postings.size < 2 || postings.map { it.id }.toSet().size != postings.size) {
                return DomainResult.Failure(DomainViolation.InvalidPostingSet)
            }

            val totals = mutableMapOf<CurrencyUnit, ExactLongAccumulator>()
            for (posting in postings) {
                val currency = posting.amount.currency
                totals.getOrPut(currency, ::ExactLongAccumulator)
                    .add(posting.amount.minorUnits)
            }

            if (totals.values.any { !it.isZero() }) {
                return DomainResult.Failure(DomainViolation.UnbalancedPostingSet)
            }

            return DomainResult.Success(PostingSet(id, postings))
        }
    }
}

enum class TransactionKind {
    OPENING_BALANCE,
    EXPENSE,
}

data class Transaction(
    val id: TransactionId,
    val ledgerId: LedgerId,
    val kind: TransactionKind,
    val currentVersionId: TransactionVersionId,
)

data class TransactionVersion(
    val id: TransactionVersionId,
    val transactionId: TransactionId,
    val versionNumber: Int,
    val postingSetId: PostingSetId,
    val times: TransactionTimes,
    val note: String? = null,
)

class FormalTransaction private constructor(
    val transaction: Transaction,
    val versions: List<TransactionVersion>,
    val postingSets: List<PostingSet>,
    private val versionsById: Map<TransactionVersionId, TransactionVersion>,
    private val postingSetsById: Map<PostingSetId, PostingSet>,
) {
    companion object {
        fun create(
            transaction: Transaction,
            versions: List<TransactionVersion>,
            postingSets: List<PostingSet>,
        ): DomainResult<FormalTransaction> {
            val versionSnapshot = versions.toList()
            val postingSetSnapshot = postingSets.toList()
            val versionsById = versionSnapshot.associateBy { it.id }
            val postingSetsById = postingSetSnapshot.associateBy { it.id }
            if (
                !validateFormalChain(
                    transaction = transaction,
                    versions = versionSnapshot,
                    postingSetCount = postingSetSnapshot.size,
                    versionsById = versionsById,
                    postingSetsById = postingSetsById,
                )
            ) {
                return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
            }

            return DomainResult.Success(
                FormalTransaction(
                    transaction = transaction,
                    versions = versionSnapshot,
                    postingSets = postingSetSnapshot,
                    versionsById = versionsById,
                    postingSetsById = postingSetsById,
                ),
            )
        }
    }

    internal fun currentPostingSet(): PostingSet {
        val currentVersion = versionsById.getValue(transaction.currentVersionId)
        return postingSetsById.getValue(currentVersion.postingSetId)
    }
}

private fun validateFormalChain(
    transaction: Transaction,
    versions: List<TransactionVersion>,
    postingSetCount: Int,
    versionsById: Map<TransactionVersionId, TransactionVersion>,
    postingSetsById: Map<PostingSetId, PostingSet>,
): Boolean {
    if (versions.isEmpty() || postingSetCount == 0) return false
    if (versionsById.size != versions.size) return false
    if (postingSetsById.size != postingSetCount) return false
    if (versions.any { it.transactionId != transaction.id || it.versionNumber < 1 }) return false
    if (versions.map { it.versionNumber }.toSet().size != versions.size) return false
    val sortedVersionNumbers = versions.map { it.versionNumber }.sorted()
    if (sortedVersionNumbers.withIndex().any { (index, number) -> number != index + 1 }) return false
    if (versions.any { it.postingSetId !in postingSetsById }) return false

    val currentVersion = versionsById[transaction.currentVersionId]
        ?: return false
    if (currentVersion.versionNumber != versions.size) return false
    return true
}
