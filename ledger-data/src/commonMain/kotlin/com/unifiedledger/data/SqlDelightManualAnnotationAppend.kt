package com.unifiedledger.data

import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import kotlin.time.Instant

/**
 * P7-08 08.B-1 (D-221; rules R1/R-5/R-6) shared manual-create annotation append seam.
 *
 * The four `manual_*_request` claim ports write, in the SAME transaction as the financial write, an
 * initial annotation aggregate (revision 1) unconditionally (empty association included, R1), the
 * tag association rows (possibly empty), the current-pointer row and the receipt. This helper owns
 * the revision/association/pointer write so all four ports share exactly one implementation. The
 * caller has already claimed the request row and persisted the formal transaction, and passes:
 *
 * - [ledgerId]/[transactionId]/[requestId]: the identity just committed;
 * - [currentVersionId]: the CURRENT version id taken from the same-transaction persisted
 *   `FormalTransaction.versions` (R-6), so the `observed_transaction_version_id` composite foreign
 *   key is satisfiable;
 * - [tagIds]/[merchantId]: the optional association carried by the request snapshot;
 * - [createdAt]: the confirming action's `LedgerClock` sample (R3).
 *
 * A transaction freshly created here has no prior annotation, so the pointer is inserted at
 * revision 1; the CAS advance path is never taken for a first-issue manual create.
 */
internal fun LedgerDatabase.appendManualCreateAnnotation(
    ledgerId: String,
    transactionId: String,
    requestId: String,
    currentVersionId: String,
    tagIds: Set<TagId>,
    merchantId: MerchantId?,
    createdAt: Instant,
) {
    ledgerQueries.insertTransactionAnnotationRevision(
        ledger_id = ledgerId,
        transaction_id = transactionId,
        annotation_revision = 1L,
        observed_transaction_version_id = currentVersionId,
        merchant_id = merchantId?.value,
        request_id = requestId,
        created_at = createdAt.toString(),
    )
    tagIds.forEach { tagId ->
        ledgerQueries.insertTransactionAnnotationTag(ledgerId, transactionId, 1L, tagId.value)
    }
    ledgerQueries.insertTransactionAnnotationCurrent(ledgerId, transactionId, 1L)
}

/**
 * R-7: the annotation claim UPDATE must change exactly one row; otherwise the whole financial
 * transaction rolls back (mirrors the claim guard at SqlDelightConfirmedManualExpenseCommitPort).
 */
internal fun LedgerDatabase.requireOneAnnotationRowChanged() {
    check(ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
        "Manual create annotation claim update must change exactly one row"
    }
}
