package com.unifiedledger.application

import com.unifiedledger.domain.Money
import kotlin.time.Instant

/**
 * D-065 projection and digest. The emitter is intentionally narrow: every projected value is
 * a JSON string, so RFC 8785 number canonicalization is not part of this contract.
 */
data class Rg09FingerprintPosting(
    val transactionId: String,
    val currentVersionId: String,
    val effectiveAt: String,
    val postingId: String,
    val accountId: String,
    val currency: String,
    val amount: String,
)

data class Rg09LedgerFingerprintProjection(
    val postings: List<Rg09FingerprintPosting>,
) {
    fun canonicalJson(): String =
        buildString {
            append("{\"postings\":[")
            postings.forEachIndexed { index, posting ->
                if (index > 0) append(',')
                append('{')
                // RFC 8785 sorts object names by UTF-16 code units.
                append("\"account_id\":").append(jcsString(posting.accountId)).append(',')
                append("\"amount\":").append(jcsString(posting.amount)).append(',')
                append("\"currency\":").append(jcsString(posting.currency)).append(',')
                append("\"current_version_id\":").append(jcsString(posting.currentVersionId)).append(',')
                append("\"effective_at\":").append(jcsString(posting.effectiveAt)).append(',')
                append("\"posting_id\":").append(jcsString(posting.postingId)).append(',')
                append("\"transaction_id\":").append(jcsString(posting.transactionId))
                append('}')
            }
            append("]}")
        }
}

object Rg09LedgerFingerprint {
    fun project(
        formalTransactions: Iterable<Rg09FormalTransactionRecord>,
        targetAt: Instant,
    ): Rg09LedgerFingerprintProjection {
        val rows =
            formalTransactions
                .asSequence()
                .mapNotNull { record ->
                    val formal = record.formalTransaction
                    val currentVersion =
                        formal.versions.singleOrNull { it.id == formal.transaction.currentVersionId }
                            ?: return@mapNotNull null
                    if (currentVersion.times.effectiveAt > targetAt) return@mapNotNull null
                    val effectiveAt = record.effectiveAtText ?: currentVersion.times.effectiveAt.toString()
                    currentVersion.postingSetId.let { postingSetId ->
                        formal.postingSets.singleOrNull { it.id == postingSetId }?.postings?.map { posting ->
                            Rg09FingerprintPosting(
                                transactionId = formal.transaction.id.value,
                                currentVersionId = currentVersion.id.value,
                                effectiveAt = effectiveAt,
                                postingId = posting.id.value,
                                accountId = posting.accountId.value,
                                currency = posting.amount.currency.code,
                                amount = formatDecimal(posting.amount),
                            )
                        }
                    }
                }.flatten()
                .sortedWith(
                    compareBy<Rg09FingerprintPosting>(
                        { it.effectiveAt },
                        { it.transactionId },
                        { it.currentVersionId },
                        { it.postingId },
                        { it.accountId },
                        { it.currency },
                        { it.amount },
                    ),
                ).toList()
        return Rg09LedgerFingerprintProjection(rows)
    }

    fun digest(projection: Rg09LedgerFingerprintProjection): String = "sha256:${Sha256.digestHex(projection.canonicalJson().encodeToByteArray())}"

    fun digest(
        formalTransactions: Iterable<Rg09FormalTransactionRecord>,
        targetAt: Instant,
    ): String = digest(project(formalTransactions, targetAt))
}

internal fun isRg09ShanghaiTimestamp(
    text: String,
    value: Instant,
): Boolean =
    text.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?\\+08:00$")) &&
        runCatching { Instant.parse(text) == value }.getOrDefault(false)

private fun formatDecimal(money: Money): String {
    val precision = money.currency.precision
    require(precision >= 0) { "RG-09 currency precision must not be negative" }
    if (precision == 0) return money.minorUnits.toString()
    val negative = money.minorUnits < 0L
    val magnitude =
        if (money.minorUnits == Long.MIN_VALUE) {
            "9223372036854775808"
        } else {
            kotlin.math.abs(money.minorUnits).toString()
        }
    val padded = magnitude.padStart(precision + 1, '0')
    val split = padded.length - precision
    return buildString {
        if (negative) append('-')
        append(padded.substring(0, split))
        append('.')
        append(padded.substring(split))
    }
}
