package com.unifiedledger.domain

import kotlin.time.Instant

enum class StoredValueActiveMode {
    ADJUSTMENT,
    RECONSTRUCTED,
}

data class StoredValueReconstructionHistory(
    val id: String,
    val event: String,
    val activeMode: StoredValueActiveMode,
    val occurredAt: Instant,
    val createdAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val createdAtText: String = createdAt.toString(),
)

/**
 * D-067 replace-not-append reconstruction entity. It owns the original adjustment ID, the
 * reconstructed transaction ID set, one active economic mode, and append-only mode history.
 * The original adjustment and every reconstructed transaction stay preserved; at any instant
 * exactly one mode owns the economic effect and double counting is forbidden.
 */
data class StoredValueReconstruction(
    val id: String,
    val replacementGroupId: String,
    val adjustmentTransactionId: TransactionId,
    val reconstructedTransactionIds: List<TransactionId>,
    val activeMode: StoredValueActiveMode,
    val history: List<StoredValueReconstructionHistory>,
)

/**
 * D-083 activation registration. The adjustment endpoint transaction is created by the
 * activation operation; reconstruction replaces it only with typed history and no in-place
 * rewrite. `reconstructedTransactionIds` stays empty for the pure adjustment baseline.
 */
fun createStoredValueReconstruction(
    id: String,
    replacementGroupId: String,
    adjustmentTransactionId: TransactionId,
    reconstructedTransactionIds: List<TransactionId>,
    createdAt: Instant,
    createdAtText: String = createdAt.toString(),
): StoredValueReconstruction =
    StoredValueReconstruction(
        id = id,
        replacementGroupId = replacementGroupId,
        adjustmentTransactionId = adjustmentTransactionId,
        reconstructedTransactionIds = reconstructedTransactionIds.toList(),
        activeMode = StoredValueActiveMode.ADJUSTMENT,
        history = listOf(
            StoredValueReconstructionHistory(
                id = "$id-created",
                event = "created",
                activeMode = StoredValueActiveMode.ADJUSTMENT,
                occurredAt = createdAt,
                createdAt = createdAt,
                occurredAtText = createdAtText,
                createdAtText = createdAtText,
            ),
        ),
    )

/**
 * Replace-not-append transition: the reconstructed endpoints become the active economic owner
 * while the original adjustment endpoint history stays appended and preserved.
 */
fun activateReconstructedMode(
    reconstruction: StoredValueReconstruction,
    id: String,
    occurredAt: Instant,
    createdAt: Instant,
    occurredAtText: String = occurredAt.toString(),
    createdAtText: String = createdAt.toString(),
): StoredValueReconstruction =
    reconstruction.copy(
        activeMode = StoredValueActiveMode.RECONSTRUCTED,
        history = reconstruction.history + StoredValueReconstructionHistory(
            id = id,
            event = "activated",
            activeMode = StoredValueActiveMode.RECONSTRUCTED,
            occurredAt = occurredAt,
            createdAt = createdAt,
            occurredAtText = occurredAtText,
            createdAtText = createdAtText,
        ),
    )
