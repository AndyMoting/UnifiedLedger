package com.unifiedledger.domain

import kotlin.time.Instant

data class LedgerId(val value: String)

data class AccountId(val value: String)

data class CategoryId(val value: String)

data class TransactionId(val value: String)

data class TransactionVersionId(val value: String)

data class PostingSetId(val value: String)

data class PostingId(val value: String)

data class StoredValueLotId(val value: String)

data class CurrencyUnit(
    val code: String,
    val precision: Int,
)

@ConsistentCopyVisibility
data class Money private constructor(
    val minorUnits: Long,
    val currency: CurrencyUnit,
) {
    companion object {
        fun ofMinor(minorUnits: Long, currency: CurrencyUnit): Money =
            Money(minorUnits, currency)
    }
}

data class TransactionTimes(
    val occurredAt: Instant,
    val statisticsAt: Instant,
    val effectiveAt: Instant,
) {
    companion object {
        fun collapsed(instant: Instant): TransactionTimes =
            TransactionTimes(
                occurredAt = instant,
                statisticsAt = instant,
                effectiveAt = instant,
            )
    }
}

internal fun checkedAdd(left: Long, right: Long): Long? {
    if (right > 0 && left > Long.MAX_VALUE - right) return null
    if (right < 0 && left < Long.MIN_VALUE - right) return null
    return left + right
}

internal fun checkedNegate(value: Long): Long? =
    if (value == Long.MIN_VALUE) null else -value

internal class ExactLongAccumulator {
    private val positiveChunks = mutableListOf<Long>()
    private val negativeChunks = mutableListOf<Long>()

    fun add(value: Long) {
        when {
            value > 0L -> addPositive(value)
            value < 0L -> addNegative(value)
        }
    }

    fun isZero(): Boolean =
        positiveChunks.isEmpty() && negativeChunks.isEmpty()

    fun exactLongOrNull(): Long? {
        var total = 0L
        val chunks = when {
            positiveChunks.isNotEmpty() -> positiveChunks
            else -> negativeChunks
        }
        for (chunk in chunks) {
            total = checkedAdd(total, chunk) ?: return null
        }
        return total
    }

    private fun addPositive(value: Long) {
        var remaining = value
        while (negativeChunks.isNotEmpty()) {
            val index = negativeChunks.lastIndex
            val combined = checkNotNull(checkedAdd(remaining, negativeChunks[index]))
            when {
                combined > 0L -> {
                    negativeChunks.removeAt(index)
                    remaining = combined
                }
                combined == 0L -> {
                    negativeChunks.removeAt(index)
                    return
                }
                else -> {
                    negativeChunks[index] = combined
                    return
                }
            }
        }
        positiveChunks.add(remaining)
    }

    private fun addNegative(value: Long) {
        var remaining = value
        while (positiveChunks.isNotEmpty()) {
            val index = positiveChunks.lastIndex
            val combined = checkNotNull(checkedAdd(remaining, positiveChunks[index]))
            when {
                combined < 0L -> {
                    positiveChunks.removeAt(index)
                    remaining = combined
                }
                combined == 0L -> {
                    positiveChunks.removeAt(index)
                    return
                }
                else -> {
                    positiveChunks[index] = combined
                    return
                }
            }
        }
        negativeChunks.add(remaining)
    }
}
