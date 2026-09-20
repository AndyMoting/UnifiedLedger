package com.unifiedledger.domain

/**
 * P7-05 correction / void / restore failure-code family frozen by the approved design
 * (spec section 4.2). The [code] is the stable, comparable token; messages are not part
 * of the contract and are never compared. The family is deliberately typed rather than a
 * free-form string so a rejection can never be confused with a success path.
 *
 * Unsupported kinds and creation lineages are typed rejections with zero writes (spec
 * section 3.1) — they are never silently accepted as a no-op.
 */
enum class P705FailureCode {
    /** The transaction is not in the request ledger. */
    P705_TRANSACTION_NOT_FOUND,

    /** The transaction kind is outside the first-slice support matrix. */
    P705_KIND_NOT_SUPPORTED,

    /** The transaction was created by an import confirmation (later slice, DP-5). */
    P705_CREATION_LINEAGE_NOT_SUPPORTED,

    /** The void target has an effective linked refund (DP-13: rejected in this slice). */
    P705_REFUND_LINKED_VOID_NOT_SUPPORTED,

    /** The request carries a field outside the frozen first-slice field set (for example `occurredAt`). */
    P705_FIELD_NOT_SUPPORTED,

    /** `expectedCurrentVersionId` is not the current version. Reported as `StaleCurrentVersion`. */
    P705_STALE_CURRENT_VERSION,

    /** A correction or a void was requested for an already voided transaction (DP-8). */
    P705_TRANSACTION_VOIDED,

    /** A restore was requested for an effective transaction, or for an already restored one. */
    P705_TRANSACTION_NOT_VOIDED,

    /** The transaction was already voided and restored; the slice allows one void plus one restore. */
    P705_VOID_CYCLE_EXHAUSTED,

    /** The void/restore reason is missing or its note exceeds the frozen bound. */
    P705_VOID_REASON_REQUIRED,

    /** A catalog reference of the new version (or of a restore revalidation) is not admissible. */
    P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,

    /**
     * Defensive dead code in this slice (spec section 3.2, DP-10): no first-slice transaction
     * has a reconciliation or evidence row, so no affected funding leg can be invalidated and
     * the D-113 correction port is never composed.
     */
    P705_MATCHED_FUNDING_LEG_CHANGED,

    /** The request is exactly equivalent to the committed state; the original receipt is returned. */
    P705_NO_CHANGE,

    /** The same request id was claimed with a different snapshot. */
    P705_REQUEST_IDENTITY_CONFLICT,

    /** A uniqueness/foreign-key/trigger backstop failed; the whole transaction rolls back. */
    P705_CONSTRAINT_VIOLATION,
    ;

    /** The stable code token (identical to the enum name by construction). */
    val code: String get() = name
}
