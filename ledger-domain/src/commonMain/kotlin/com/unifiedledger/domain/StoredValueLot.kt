package com.unifiedledger.domain

import kotlin.time.Instant

data class StoredValueLotHistory(
    val id: String,
    val event: String,
    val transactionId: TransactionId,
    val amount: Money,
    val remainingFaceValue: Money,
    val occurredAt: Instant,
    val createdAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val createdAtText: String = createdAt.toString(),
    val compositionStatus: String? = null,
)

data class StoredValueLot(
    val id: StoredValueLotId,
    val rechargeTransactionId: TransactionId?,
    val loadedAt: Instant,
    val expiresAt: Instant,
    val faceValue: Money,
    val remainingFaceValue: Money,
    val paidAmount: Money?,
    val bonusAmount: Money?,
    val remainingPaidAmount: Money?,
    val remainingBonusAmount: Money?,
    val compositionStatus: String,
    val history: List<StoredValueLotHistory>,
    val merchantId: String?,
    val loadedAtText: String = loadedAt.toString(),
    val expiresAtText: String = expiresAt.toString(),
)

/**
 * D-064 default batch allocation order: earliest expiry, then earliest loaded time, then
 * stable batch ID. Allocation only consumes face value; missing paid/bonus composition stays
 * unknown and never becomes paid-first or bonus-first.
 */
fun defaultLotOrder(lots: List<StoredValueLot>): List<StoredValueLot> =
    lots.sortedWith(
        compareBy<StoredValueLot>(
            { it.expiresAt },
            { it.loadedAt },
            { it.id.value },
        ),
    )
