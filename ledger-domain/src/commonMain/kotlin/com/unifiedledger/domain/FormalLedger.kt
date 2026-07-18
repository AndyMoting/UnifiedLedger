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
)

class FormalTransaction private constructor(
    val transaction: Transaction,
    val versions: List<TransactionVersion>,
    val postingSets: List<PostingSet>,
) {
    companion object {
        fun create(
            transaction: Transaction,
            versions: List<TransactionVersion>,
            postingSets: List<PostingSet>,
        ): DomainResult<FormalTransaction> {
            val versionSnapshot = versions.toList()
            val postingSetSnapshot = postingSets.toList()
            if (!validateFormalChain(transaction, versionSnapshot, postingSetSnapshot)) {
                return DomainResult.Failure(DomainViolation.InvalidFormalTransaction)
            }

            return DomainResult.Success(
                FormalTransaction(transaction, versionSnapshot, postingSetSnapshot),
            )
        }
    }

    internal fun currentPostingSet(): PostingSet {
        val currentVersion = versions.single { it.id == transaction.currentVersionId }
        return postingSets.single { it.id == currentVersion.postingSetId }
    }
}

private fun validateFormalChain(
    transaction: Transaction,
    versions: List<TransactionVersion>,
    postingSets: List<PostingSet>,
): Boolean {
    if (versions.isEmpty() || postingSets.isEmpty()) return false
    if (versions.map { it.id }.toSet().size != versions.size) return false
    if (postingSets.map { it.id }.toSet().size != postingSets.size) return false
    if (versions.any { it.transactionId != transaction.id || it.versionNumber < 1 }) return false
    if (versions.map { it.versionNumber }.toSet().size != versions.size) return false
    if (versions.any { version -> postingSets.none { it.id == version.postingSetId } }) return false

    val currentVersion = versions.singleOrNull { it.id == transaction.currentVersionId }
        ?: return false
    return postingSets.count { it.id == currentVersion.postingSetId } == 1
}
