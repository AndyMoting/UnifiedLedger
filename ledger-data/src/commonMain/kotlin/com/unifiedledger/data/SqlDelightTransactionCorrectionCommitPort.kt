package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionCommitPort
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.P408CorrectLinkRequest
import com.unifiedledger.application.P408CorrectionCommitPort
import com.unifiedledger.application.P408CorrectionReason
import com.unifiedledger.application.P408CorrectionResultState
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.TransactionCorrectionPlan
import com.unifiedledger.application.TransactionCorrectionReceipt
import com.unifiedledger.application.TransactionCorrectionRequestIdentity
import com.unifiedledger.application.TransactionCorrectionRequestSnapshot
import com.unifiedledger.application.TransactionCorrectionTarget
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFact
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.TransactionVoidState
import com.unifiedledger.domain.affectedFundingLegs
import com.unifiedledger.domain.isP705CorrectionSupportedKind

/**
 * P7-05.B correction commit boundary (spec sections 3.2/4.3; D-156; DP-10 narrowed and the
 * D-113 invalidation composed by the transfer slice, D-228 J3 candidate (a)).
 *
 * Claim-first and request-idempotent: the request row is claimed before anything else, and a
 * lost claim is resolved by comparing the stored snapshot, so replay is decided before any CAS
 * evaluation. The new version is appended by `copyCurrentVersionWithNewPostingSet`, whose
 * join on `ledger_transaction_current_version` makes the CAS part of the statement itself:
 * zero changed rows is `StaleCurrentVersion` with nothing written (the stale exit throws a
 * private control signal so the whole transaction - claim and any invalidation writes - rolls
 * back, and a failed invalidation throws the same signal carrying its typed rejection, so a
 * rejection can never leave an earlier leg's invalidation in effect; the observable contract
 * stays the frozen zero-write family). `occurred_at` and `effective_at` are copied verbatim;
 * only `statistics_at` follows the request. Lending and import owners are never touched; the
 * reconciliation/evidence owners are touched exactly by the narrowed DP-10 composition below.
 *
 * DP-10 narrowed (D-228 J3 rendering): an affected funding leg - a real-account leg of the
 * new version whose `(accountId, amount, currency)` changed - is no longer a rejection. The
 * frozen `affectedFundingLegs` derivation decides, mirrored onto the old side to find the
 * replaced postings that carry the reconciliation rows:
 *
 * - a replaced old leg with an *active* evidence link has that link invalidated through the
 *   reused D-113 `SqlDelightCorrectionStore` (invalidation-only: `POSTING_REPLACED` reason,
 *   `MISSING` result state, the posting's existing reconciliation id), inside this same
 *   transaction and *before* the version append - `selectP408PostingIntegrity` only resolves
 *   current-version postings, so the order is forced, and the correction itself succeeds;
 * - a replaced old leg with only a `PENDING` placeholder row (or no reconciliation row at
 *   all) has no invalidation object and proceeds untouched - it is never rejected and never
 *   reported through `P408_INVALIDATE_LINK_NOT_ACTIVE`;
 * - an empty affected set (note-only/statistics-only corrections) is never rejected
 *   (D-158 fourth residual, T-12) and changes no reconciliation or evidence row.
 *
 * `P705_MATCHED_FUNDING_LEG_CHANGED` stays in the frozen failure-code family but has no
 * trigger point left in this port. Fail-closed: when a link genuinely needs invalidation but
 * no D-113 store is wired (the platform-configured factory cannot construct one driverless),
 * the correction is rejected through the constraint backstop rather than silently leaving a
 * stale match - today that gap is unreachable on the platform path because no product writer
 * creates evidence links there.
 */
class SqlDelightTransactionCorrectionCommitPort private constructor(
    private val database: LedgerDatabase,
    private val reconciliationCorrection: P408CorrectionCommitPort?,
) : CorrectTransactionVersionCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(
        database,
        SqlDelightCorrectionStore(database, driver),
    ) {
        configureSqliteConnection(driver)
    }

    companion object {
        /**
         * Platform-configured factory (the `forPlatformConfiguredDatabase` precedent of the four
         * manual commit ports): for a database whose connection the platform already configured —
         * the Android `ForeignKeysCallback` path, which intentionally sets no JDBC busy timeout —
         * this factory skips the JDBC-only `configureSqliteConnection` PRAGMA setup and only
         * constructs the port. Internal like its precedents: the platform data-assembly handle
         * (ledger-data androidMain) calls it; app composition roots never see the driver
         * (P7-05 slice 1b wiring, D-156/D-158).
         *
         * The D-113 invalidation store is not wired on this path (its driverless constructor is
         * private to the store), so a correction that would need an invalidation is the
         * fail-closed constraint rejection described on the class - unreachable while no product
         * writer creates evidence links on the platform.
         */
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightTransactionCorrectionCommitPort = SqlDelightTransactionCorrectionCommitPort(database, null)
    }

    override fun commitOnce(
        identity: TransactionCorrectionRequestIdentity,
        requestSnapshot: TransactionCorrectionRequestSnapshot,
        plan: (TransactionCorrectionTarget) -> TransactionCorrectionPlan,
    ): CorrectTransactionVersionResult {
        require(identity.ledgerId == requestSnapshot.ledgerId) {
            "Request identity and snapshot must belong to the same ledger"
        }
        return try {
            database.transactionWithResult {
                database.ledgerQueries.claimTransactionCorrectionRequest(
                    ledger_id = identity.ledgerId.value,
                    request_id = identity.requestId.value,
                    transaction_id = requestSnapshot.transactionId.value,
                    expected_current_version_id = requestSnapshot.expectedCurrentVersionId.value,
                    note = requestSnapshot.note,
                    statistics_at = requestSnapshot.statisticsAt.toString(),
                    amount_minor = requestSnapshot.amount.minorUnits,
                    currency_code = requestSnapshot.amount.currency.code,
                    currency_precision =
                        requestSnapshot.amount.currency.precision
                            .toLong(),
                    category_id = requestSnapshot.categoryId?.value,
                    funding_account_id = requestSnapshot.fundingAccountId.value,
                    confirmation_marker = EXPLICIT_MANUAL_SAVE_MARKER,
                    destination_account_id = requestSnapshot.destinationAccountId,
                    fee_minor = requestSnapshot.feeMinor,
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveExisting(identity, requestSnapshot)
                }

                val target =
                    database.ledgerQueries
                        .transactionCorrectionTarget(identity.ledgerId.value, requestSnapshot.transactionId.value)
                        .executeAsOneOrNull()
                if (target == null) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_TRANSACTION_NOT_FOUND)
                }
                val kind = TransactionKind.valueOf(target.kind)
                if (!isP705CorrectionSupportedKind(kind)) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_KIND_NOT_SUPPORTED)
                }
                // Transfer slice (spec section 8.3) write-time row-shape invariants, fail-closed
                // and enforced by kind inside this commit transaction: the request table has no
                // kind column, so no table-level CHECK can express them. A transfer row requires
                // a destination account, a non-negative fee and a category exactly when the fee
                // is positive; an ordinary row requires no destination, a zero fee and always a
                // category. The claim row is discarded with the typed rejection, so the identity
                // stays retryable and nothing else is written.
                requestRowShapeRejection(kind, requestSnapshot)?.let { shapeRejection ->
                    return@transactionWithResult reject(identity, shapeRejection)
                }
                if (importCreationConfirmationCount(requestSnapshot) > 0L) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_CREATION_LINEAGE_NOT_SUPPORTED)
                }
                if (!voidState(requestSnapshot).isEffective) {
                    return@transactionWithResult reject(identity, P705FailureCode.P705_TRANSACTION_VOIDED)
                }

                val oldPostings = currentVersionPostings(requestSnapshot)
                val planned =
                    plan(
                        TransactionCorrectionTarget(
                            transactionId = requestSnapshot.transactionId,
                            kind = kind,
                            currentVersionId = TransactionVersionId(target.current_version_id),
                            postings = oldPostings,
                        ),
                    )
                when (planned) {
                    is TransactionCorrectionPlan.Rejected -> reject(identity, planned.code)
                    is TransactionCorrectionPlan.Commit -> {
                        // The ledger's zero-sum invariant is enforced at this write boundary, not
                        // only by the caller: the plan callback is a public port, so the postings
                        // it returns must form a real posting set (at least two legs, unique ids,
                        // per-currency zero sum) before any version row binds them. Validation
                        // runs before the CAS copy so a violation cannot leave a version row
                        // behind; the failure is an invariant backstop, reported through the same
                        // code as the other constraint backstops (spec section 4.2).
                        if (
                            PostingSet.create(
                                PostingSetId(planned.postingSetId.value),
                                planned.postings,
                            ) is DomainResult.Failure
                        ) {
                            return@transactionWithResult reject(identity, P705FailureCode.P705_CONSTRAINT_VIOLATION)
                        }
                        // DP-10 narrowed + D-113 composition (D-228 J3): the frozen affected-leg
                        // derivation decides which replaced old postings carry matches to
                        // invalidate - before the version append, inside this same transaction.
                        // A failure inside the composition throws the rollback signal carrying
                        // the typed rejection, so the whole transaction - claim and every
                        // invalidation write already applied - rolls back (all-or-nothing: a
                        // rejected correction can never leave a partially invalidated state
                        // behind; the D-113 store additionally rolled its own nested writes
                        // back to its savepoint).
                        invalidateReplacedFundingLegMatches(identity, requestSnapshot, oldPostings, planned.postings)
                        // Frozen write form (spec section 3.2): a correction whose
                        // (accountId, amount, currency) leg set is unchanged keeps the current
                        // posting set, so posting identity — and with it the basis of "unchanged
                        // funding legs keep their reconciliation rows and evidence links" — is not
                        // rebound. Only a real leg change allocates the fresh set.
                        if (planned.reuseCurrentPostingSet) {
                            database.ledgerQueries.copyCurrentVersionReusingPostingSet(
                                version_id = planned.receipt.versionId.value,
                                statistics_at = requestSnapshot.statisticsAt.toString(),
                                note = requestSnapshot.note,
                                transaction_id = requestSnapshot.transactionId.value,
                                ledger_id = identity.ledgerId.value,
                                expected_current_version_id = requestSnapshot.expectedCurrentVersionId.value,
                            )
                        } else {
                            // CAS: the copy binds the expected current version in its join, so a
                            // concurrent winner makes it change zero rows. Nothing but the claim has
                            // been written at this point, so the stale path is a zero-write path.
                            database.ledgerQueries.copyCurrentVersionWithNewPostingSet(
                                version_id = planned.receipt.versionId.value,
                                new_posting_set_id = planned.postingSetId.value,
                                statistics_at = requestSnapshot.statisticsAt.toString(),
                                note = requestSnapshot.note,
                                transaction_id = requestSnapshot.transactionId.value,
                                ledger_id = identity.ledgerId.value,
                                expected_current_version_id = requestSnapshot.expectedCurrentVersionId.value,
                            )
                        }
                        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                            // The CAS lost. Throw (do not return) so the transaction rolls back the
                            // claim *and* any invalidation writes already applied above: a stale
                            // correction must leave no partial state (version not appended, match
                            // not invalidated). The caught signal below returns the frozen
                            // `StaleCurrentVersion`; the rolled-back claim is the same zero-write
                            // outcome the discarded-claim delete used to produce.
                            throw RollbackSignal(CorrectTransactionVersionResult.StaleCurrentVersion)
                        }
                        if (!planned.reuseCurrentPostingSet) {
                            database.ledgerQueries.insertPostingSet(
                                posting_set_id = planned.postingSetId.value,
                                ledger_id = identity.ledgerId.value,
                            )
                            planned.postings.forEachIndexed { index, posting ->
                                database.ledgerQueries.insertPosting(
                                    posting_id = posting.id.value,
                                    posting_set_id = planned.postingSetId.value,
                                    ledger_id = identity.ledgerId.value,
                                    posting_index = index.toLong(),
                                    account_id = posting.accountId.value,
                                    amount_minor = posting.amount.minorUnits,
                                    currency_code = posting.amount.currency.code,
                                    currency_precision =
                                        posting.amount.currency.precision
                                            .toLong(),
                                )
                            }
                        }
                        database.ledgerQueries.compareAndSetCurrentVersion(
                            planned.receipt.versionId.value,
                            requestSnapshot.transactionId.value,
                            identity.ledgerId.value,
                            requestSnapshot.expectedCurrentVersionId.value,
                        )
                        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                            "Correction CAS must advance exactly one current-version row"
                        }
                        database.ledgerQueries.insertTransactionCorrectionReceipt(
                            ledger_id = identity.ledgerId.value,
                            request_id = identity.requestId.value,
                            confirmation_id = planned.receipt.confirmationId.value,
                            transaction_id = planned.receipt.transactionId.value,
                            version_id = planned.receipt.versionId.value,
                            expected_current_version_id = planned.receipt.expectedCurrentVersionId.value,
                        )
                        CorrectTransactionVersionResult.Created(planned.receipt)
                    }
                }
            }
        } catch (signal: RollbackSignal) {
            // The transaction above rolled back in full (claim and any invalidation writes); both
            // signal origins - the stale CAS exit and a failed invalidation - are zero-write
            // outcomes: the identity stays retryable and the carried result is the frozen one.
            signal.result
        } catch (failure: Exception) {
            if (isSqliteConstraintFailure(failure)) {
                // The whole transaction already rolled back, so the identity stays retryable and
                // no partial version/posting/receipt row survived.
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION)
            } else {
                throw failure
            }
        }
    }

    /**
     * The narrowed DP-10 gate and D-113 composition (D-228 J3 candidate (a)), executed after the
     * plan validates and before the version append. Returns normally to proceed - including the
     * no-object rows B/C of the behavior matrix - and throws [RollbackSignal] carrying the
     * fail-closed rejection when the composition fails, so every invalidation write already
     * applied for earlier legs rolls back with the transaction.
     *
     * The old-side replaced legs come from the frozen [affectedFundingLegs] derivation applied
     * with swapped sides (new as the matched-against set): that mirror returns exactly the old
     * real-account postings whose `(accountId, amount, currency)` tuple did not survive into the
     * new version - the postings that can carry reconciliation rows - without duplicating the
     * derivation's logic. The real-account id set is read from the same authoritative
     * `catalog_account` rows the admission reader loads (`selectCatalogAccounts`).
     */
    private fun invalidateReplacedFundingLegMatches(
        identity: TransactionCorrectionRequestIdentity,
        requestSnapshot: TransactionCorrectionRequestSnapshot,
        oldPostings: List<Posting>,
        newPostings: List<Posting>,
    ) {
        val realAccountIds =
            database.ledgerQueries
                .selectCatalogAccounts(identity.ledgerId.value) { accountId, _, _, _, _, _, realAccount, _, _, _ ->
                    accountId to realAccount
                }.executeAsList()
                .filter { (_, realAccount) -> realAccount == 1L }
                .mapTo(HashSet()) { (accountId, _) -> AccountId(accountId) }
        val replacedOldLegs = affectedFundingLegs(newPostings, oldPostings, realAccountIds)
        for (replacedLeg in replacedOldLegs) {
            // Row B zero-row case: no reconciliation row on the posting, nothing to invalidate.
            val reconciliation =
                database.ledgerQueries
                    .selectP408PostingReconciliation(identity.ledgerId.value, replacedLeg.id.value)
                    .executeAsOneOrNull()
                    ?: continue
            val activeLinks =
                P408EvidenceResponsibility.entries
                    .flatMap { responsibility ->
                        database.ledgerQueries
                            .selectP408ActiveLinksForPostingResponsibility(
                                identity.ledgerId.value,
                                replacedLeg.id.value,
                                responsibility.storageValue,
                            ).executeAsList()
                    }.sortedBy { activeLink -> activeLink.link_id }
            for (activeLink in activeLinks) {
                val correctionStore =
                    reconciliationCorrection
                        ?: throw RollbackSignal(CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION))
                val invalidation =
                    P408CorrectLinkRequest(
                        ledgerId = identity.ledgerId.value,
                        // Derived from the correction's own fresh request identity, so every
                        // invalidation request id is unique per committed correction and a
                        // replayed correction never reaches this fresh path.
                        requestId = "${identity.requestId.value}:invalidate:${activeLink.link_id}",
                        evidenceId = activeLink.evidence_id,
                        previousLinkId = activeLink.link_id,
                        reason = P408CorrectionReason.POSTING_REPLACED,
                        affectedPostingId = replacedLeg.id.value,
                        // Invalidation-only: the replaced posting goes back to unmatched material
                        // (`MISSING`); the CHECKED successor re-proof is a later slice. The
                        // posting's existing reconciliation id is mandatory - the store's
                        // insert-or-ignore plus id comparison pins it.
                        resultState = P408CorrectionResultState.MISSING,
                        confirmedAt = requestSnapshot.statisticsAt.toString(),
                        reconciliationId = reconciliation.reconciliation_id,
                    )
                if (correctionStore.correct(invalidation) !is P408ReconciliationResult.Accepted) {
                    // The D-113 store rolled its nested writes back to its savepoint; throwing
                    // here rolls back the correction's own transaction too, so a rejection can
                    // never leave an earlier leg's successful invalidation in effect
                    // (all-or-nothing; the correctness gap the non-composing reading would leave).
                    throw RollbackSignal(CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION))
                }
            }
        }
    }

    private fun importCreationConfirmationCount(requestSnapshot: TransactionCorrectionRequestSnapshot): Long =
        database.ledgerQueries
            .importCreationConfirmationCountForTransaction(
                requestSnapshot.ledgerId.value,
                requestSnapshot.transactionId.value,
            ).executeAsOne()

    /**
     * The spec section 8.3 write-time row-shape invariants by kind (fail-closed): a transfer row
     * requires a non-null destination account, a non-negative fee and a category exactly when
     * the fee is positive; an ordinary row requires no destination account, a zero fee and
     * always a category. An inconsistent target state is reported through the frozen field code
     * (spec section 9's reuse ruling); `null` means the row shape matches its kind.
     */
    private fun requestRowShapeRejection(
        kind: TransactionKind,
        snapshot: TransactionCorrectionRequestSnapshot,
    ): P705FailureCode? =
        if (kind == TransactionKind.ACCOUNT_TRANSFER) {
            when {
                snapshot.destinationAccountId == null -> P705FailureCode.P705_FIELD_NOT_SUPPORTED
                snapshot.feeMinor < 0L -> P705FailureCode.P705_FIELD_NOT_SUPPORTED
                (snapshot.feeMinor > 0L) != (snapshot.categoryId != null) -> P705FailureCode.P705_FIELD_NOT_SUPPORTED
                else -> null
            }
        } else {
            when {
                snapshot.destinationAccountId != null -> P705FailureCode.P705_FIELD_NOT_SUPPORTED
                snapshot.feeMinor != 0L -> P705FailureCode.P705_FIELD_NOT_SUPPORTED
                snapshot.categoryId == null -> P705FailureCode.P705_FIELD_NOT_SUPPORTED
                else -> null
            }
        }

    /** The current version's postings, the "old" side of the frozen write-form field diff. */
    private fun currentVersionPostings(requestSnapshot: TransactionCorrectionRequestSnapshot): List<Posting> =
        database.ledgerQueries
            .currentVersionPostingsForTransaction(
                requestSnapshot.ledgerId.value,
                requestSnapshot.transactionId.value,
            ) { postingId, _, accountId, amountMinor, currencyCode, currencyPrecision ->
                Posting(
                    id = PostingId(postingId),
                    accountId = AccountId(accountId),
                    amount =
                        Money.ofMinor(
                            amountMinor,
                            CurrencyUnit(currencyCode, currencyPrecision.toInt()),
                        ),
                )
            }.executeAsList()

    private fun voidState(requestSnapshot: TransactionCorrectionRequestSnapshot): TransactionVoidState =
        TransactionVoidState.of(
            database.ledgerQueries
                .transactionVoidFactsForTransaction(
                    requestSnapshot.ledgerId.value,
                    requestSnapshot.transactionId.value,
                ) { sequence, factKind, _, _, _, _, _ ->
                    TransactionVoidFact(
                        sequence = sequence.toInt(),
                        factKind =
                            TransactionVoidFactKind.fromStorage(factKind)
                                ?: TransactionVoidFactKind.VOID,
                    )
                }.executeAsList(),
        )

    private fun reject(
        identity: TransactionCorrectionRequestIdentity,
        code: P705FailureCode,
    ): CorrectTransactionVersionResult {
        database.ledgerQueries.deleteTransactionCorrectionRequest(identity.ledgerId.value, identity.requestId.value)
        return CorrectTransactionVersionResult.Rejected(code)
    }

    private fun resolveExisting(
        identity: TransactionCorrectionRequestIdentity,
        snapshot: TransactionCorrectionRequestSnapshot,
    ): CorrectTransactionVersionResult {
        val existing =
            checkNotNull(
                database.ledgerQueries
                    .selectCommittedTransactionCorrectionRequest(identity.ledgerId.value, identity.requestId.value) {
                        transactionId,
                        expectedCurrentVersionId,
                        note,
                        statisticsAt,
                        amountMinor,
                        currencyCode,
                        currencyPrecision,
                        categoryId,
                        fundingAccountId,
                        confirmationMarker,
                        destinationAccountId,
                        feeMinor,
                        confirmationId,
                        versionId,
                        ->
                        StoredCorrection(
                            transactionId = transactionId,
                            expectedCurrentVersionId = expectedCurrentVersionId,
                            note = note,
                            statisticsAt = statisticsAt,
                            amountMinor = amountMinor,
                            currencyCode = currencyCode,
                            currencyPrecision = currencyPrecision,
                            // category_id is storage-nullable for transfer rows (a fee == 0
                            // transfer stores no category, spec section 8.3); the equivalent
                            // replay comparison below compares the column exactly as stored, so
                            // an ordinary row's non-null invariant is a property of the write
                            // boundary above, not of this read.
                            categoryId = categoryId,
                            fundingAccountId = fundingAccountId,
                            confirmationMarker = confirmationMarker,
                            destinationAccountId = destinationAccountId,
                            feeMinor = feeMinor,
                            confirmationId = confirmationId,
                            versionId = versionId,
                        )
                    }.executeAsOneOrNull(),
            ) { "Committed correction is missing its receipt" }
        return if (existing.matches(snapshot)) {
            CorrectTransactionVersionResult.NoChange(
                TransactionCorrectionReceipt(
                    confirmationId = ConfirmationId(existing.confirmationId),
                    transactionId = TransactionId(existing.transactionId),
                    versionId = TransactionVersionId(existing.versionId),
                    expectedCurrentVersionId = TransactionVersionId(existing.expectedCurrentVersionId),
                ),
            )
        } else {
            CorrectTransactionVersionResult.RequestIdentityConflict(identity)
        }
    }
}

private const val EXPLICIT_MANUAL_SAVE_MARKER = "explicit_manual_save"

/**
 * Control-flow signal of the zero-write exits: thrown where the transaction must roll back in
 * full - the stale CAS exit and a failed D-113 invalidation (all-or-nothing) - and caught above,
 * which returns the frozen result the signal carries. Never escapes the port.
 */
private class RollbackSignal(
    val result: CorrectTransactionVersionResult,
) : RuntimeException()

private data class StoredCorrection(
    val transactionId: String,
    val expectedCurrentVersionId: String,
    val note: String?,
    val statisticsAt: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val categoryId: String?,
    val fundingAccountId: String,
    val confirmationMarker: String,
    val destinationAccountId: String?,
    val feeMinor: Long,
    val confirmationId: String,
    val versionId: String,
) {
    fun matches(snapshot: TransactionCorrectionRequestSnapshot): Boolean =
        transactionId == snapshot.transactionId.value &&
            expectedCurrentVersionId == snapshot.expectedCurrentVersionId.value &&
            note == snapshot.note &&
            statisticsAt == snapshot.statisticsAt.toString() &&
            amountMinor == snapshot.amount.minorUnits &&
            currencyCode == snapshot.amount.currency.code &&
            currencyPrecision ==
            snapshot.amount.currency.precision
                .toLong() &&
            categoryId == snapshot.categoryId?.value &&
            fundingAccountId == snapshot.fundingAccountId.value &&
            destinationAccountId == snapshot.destinationAccountId &&
            feeMinor == snapshot.feeMinor &&
            confirmationMarker == EXPLICIT_MANUAL_SAVE_MARKER
}
