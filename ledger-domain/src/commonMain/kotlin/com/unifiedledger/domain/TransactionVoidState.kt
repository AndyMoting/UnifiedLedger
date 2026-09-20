package com.unifiedledger.domain

/**
 * P7-05 logical-void domain vocabulary (spec sections 3.3/4.1). The void/restore facts
 * themselves belong to the persistence/application boundary, but the *effective predicate*
 * has exactly one domain representation here: [TransactionVoidState.isEffective]. The SQL
 * predicate (`transaction_effective_state` in `Ledger.sq`) and this value type must stay
 * equivalent for every fact set (acceptance vector V-23).
 */
enum class TransactionVoidFactKind(
    val storageValue: String,
) {
    VOID("void"),
    RESTORE("restore"),
    ;

    companion object {
        /** `null` when the stored token is outside the frozen two-value domain. */
        fun fromStorage(value: String): TransactionVoidFactKind? = entries.firstOrNull { it.storageValue == value }
    }
}

/** One append-only void/restore fact in sequence order; `sequence` starts at 1. */
data class TransactionVoidFact(
    val sequence: Int,
    val factKind: TransactionVoidFactKind,
)

/**
 * The void/restore fact sequence of one transaction. A transaction is effective iff the
 * last fact is not a void; no fact at all means effective (spec section 3.3).
 *
 * The value type is total over fact sequences: it deliberately does not require
 * alternation or the slice's depth bound, because those are enforced where the facts are
 * appended (the request precondition and the database guard), not where they are read.
 */
class TransactionVoidState private constructor(
    val facts: List<TransactionVoidFact>,
) {
    /** The latest fact kind, or `null` when the transaction has no void/restore fact. */
    val latestFactKind: TransactionVoidFactKind? = facts.lastOrNull()?.factKind

    /** The frozen effective predicate: no fact, or a latest fact that is a restore. */
    val isEffective: Boolean = latestFactKind != TransactionVoidFactKind.VOID

    /** The slice's append depth (0, 1 or 2); deeper sequences are rejected where they are appended. */
    val depth: Int = facts.size

    companion object {
        /** No fact at all: the transaction is effective. */
        val effective: TransactionVoidState = TransactionVoidState(emptyList())

        /** Orders the facts by sequence before deriving the predicate, so callers need no ordering. */
        fun of(facts: List<TransactionVoidFact>): TransactionVoidState = TransactionVoidState(facts.sortedBy { it.sequence })
    }
}

/**
 * The frozen typed reason code set of a void/restore fact (DP-11: the reason is mandatory and
 * the code set is closed). The storage token is the stable persisted form; the domain type
 * below is the single alternative representation (equivalence is asserted by V-22).
 *
 * The five codes below and [VOID_REASON_NOTE_MAX_LENGTH] are the values this batch froze at
 * implementation time: D-156 delegates the note bound to implementation and leaves the code
 * list open, so they are batch-frozen pending the design-spec revision that registers them —
 * not spec-derived values, and not to be read as frozen by the approved design itself.
 */
enum class VoidReasonCode(
    val storageValue: String,
) {
    MIS_ENTERED("mis_entered"),
    DUPLICATE_ENTRY("duplicate_entry"),
    NO_LONGER_APPLICABLE("no_longer_applicable"),
    VOIDED_IN_ERROR("voided_in_error"),
    OTHER("other"),
    ;

    companion object {
        /** `null` when the stored token is outside the frozen code set. */
        fun fromStorage(value: String): VoidReasonCode? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * The frozen bound of the optional free-text reason note: 200 characters, frozen by this
 * batch at implementation time (the design delegates the bound here) pending the spec
 * revision. It is the only place that length is defined for the product vocabulary.
 */
const val VOID_REASON_NOTE_MAX_LENGTH: Int = 200

/**
 * The single domain representation of a void/restore reason: a frozen typed code plus an
 * optional bounded note. Reasons and notes are shown only through the recycle-bin read
 * path; they must never reach logs or test failure messages (spec section 4.4).
 */
data class VoidReason(
    val code: VoidReasonCode,
    val note: String? = null,
)

/**
 * `null` when the reason is admissible; otherwise the frozen rejection code. A missing
 * reason (`null`), a blank or over-long note is `P705_VOID_REASON_REQUIRED` with zero writes.
 */
fun voidReasonRejection(reason: VoidReason?): P705FailureCode? {
    if (reason == null) return P705FailureCode.P705_VOID_REASON_REQUIRED
    val note = reason.note ?: return null
    if (note.isBlank()) return P705FailureCode.P705_VOID_REASON_REQUIRED
    if (note.length > VOID_REASON_NOTE_MAX_LENGTH) return P705FailureCode.P705_VOID_REASON_REQUIRED
    return null
}

/**
 * The append precondition of the first slice (DP-8): at most one void plus one restore per
 * transaction. Returns the typed rejection for the requested next fact, or `null` when the
 * append is admissible. This is the application-side twin of the `transaction_void_fact`
 * sequence guard, so a violation is a typed rejection rather than a raw constraint error.
 */
fun voidAppendRejection(
    state: TransactionVoidState,
    factKind: TransactionVoidFactKind,
): P705FailureCode? =
    when (factKind) {
        TransactionVoidFactKind.VOID ->
            when {
                state.isEffective && state.depth == 0 -> null
                state.isEffective -> P705FailureCode.P705_VOID_CYCLE_EXHAUSTED
                else -> P705FailureCode.P705_TRANSACTION_VOIDED
            }
        TransactionVoidFactKind.RESTORE ->
            when {
                !state.isEffective -> null
                state.depth == 0 -> P705FailureCode.P705_TRANSACTION_NOT_VOIDED
                else -> P705FailureCode.P705_TRANSACTION_NOT_VOIDED
            }
    }
