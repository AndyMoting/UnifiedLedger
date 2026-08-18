package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import kotlin.time.Instant

data class Rg03TransferCommitIds(
    val confirmationId: ConfirmationId,
    val transferIds: AccountTransferIds,
    val sourceReconciliationId: String,
    val destinationReconciliationId: String,
    val candidateStatusId: String? = null,
    val sourceEvidenceLinkId: String? = null,
)

data class Rg03SourceCommitIds(
    val candidateId: CandidateId,
    val candidateStatusId: String,
)

data class Rg03MirrorCommitIds(val evidenceLinkId: String)

interface Rg03IdentitySource {
    fun source(requestId: RequestId): Rg03SourceCommitIds
    fun transfer(requestId: RequestId): Rg03TransferCommitIds
    fun mirror(requestId: RequestId): Rg03MirrorCommitIds
}

internal enum class Rg03FailurePoint { SOURCE_AFTER_CANDIDATE, CONFIRMATION_AFTER_FORMAL, MIRROR_AFTER_LINK }
internal fun interface Rg03FailureInjector { fun failAt(point: Rg03FailurePoint) }
private val NO_RG03_FAILURE = Rg03FailureInjector { }

class SqlDelightRg03TransferStore private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val identitySource: Rg03IdentitySource,
    private val failureInjector: Rg03FailureInjector,
) : Rg03PreparedOperationCommitPort, Rg03CandidateRecoveryPort, Rg03MirrorBindingPort {
    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        identitySource: Rg03IdentitySource,
    ) : this(database, catalog, identitySource, NO_RG03_FAILURE) {
        configureSqliteConnection(driver)
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        identitySource: Rg03IdentitySource,
        failureInjector: Rg03FailureInjector,
    ) : this(database, catalog, identitySource, failureInjector) {
        configureSqliteConnection(driver)
    }

    override fun commit(operation: Rg03PreparedOperation): Rg03ExecutionResult = when (operation) {
        is Rg03PreparedOperation.CreateManual -> commitManual(operation.snapshot)
        is Rg03PreparedOperation.StoreSource -> commitSource(operation.snapshot)
        is Rg03PreparedOperation.ConfirmCandidate -> commitCandidate(operation.requestId, operation.candidate)
        is Rg03PreparedOperation.MergeMirror -> commitMirror(operation.snapshot, operation.target)
    }

    override fun load(
        ledgerId: LedgerId,
        candidateId: CandidateId,
    ): Rg03PersistedTransferCandidate? = database.ledgerQueries.selectRg03Candidate(
        ledgerId.value,
        candidateId.value,
    ) { storedCandidateId, status, sourceId, evidenceId, observedAt, sourceAccountId,
            sourceDebitMinor, currencyCode, currencyPrecision, _, destinationAccountId,
            destinationCreditMinor, feeMinor, feeCategoryId ->
        val currency = CurrencyUnit(currencyCode, currencyPrecision.toInt())
        Rg03PersistedTransferCandidate(
            ledgerId = ledgerId,
            candidateId = CandidateId(storedCandidateId),
            status = CandidateStatus.valueOf(checkNotNull(status)),
            sourceId = SourceRecordId(sourceId),
            evidenceId = EvidenceId(evidenceId),
            sourceAccountId = AccountId(sourceAccountId),
            destinationAccountId = destinationAccountId?.let(::AccountId),
            sourceDebit = Money.ofMinor(sourceDebitMinor, currency),
            destinationCredit = destinationCreditMinor?.let { Money.ofMinor(it, currency) },
            fee = feeMinor?.let { Money.ofMinor(it, currency) },
            feeCategoryId = CategoryId(feeCategoryId),
            observedAt = Instant.parse(observedAt),
            originalObservedAtText = observedAt,
        )
    }.executeAsOneOrNull()

    override fun resolve(
        ledgerId: LedgerId,
        scope: Rg03MirrorScope,
    ): Rg03MirrorBindingResult {
        val targets = database.ledgerQueries.selectRg03MirrorBindings(ledgerId.value, scope.candidateId.value) {
                candidateId, transactionId, postingId, destinationAccountId,
                destinationCreditMinor, currencyCode, currencyPrecision ->
            val currency = CurrencyUnit(currencyCode, currencyPrecision.toInt())
            Rg03MirrorTarget(
                candidateId = CandidateId(checkNotNull(candidateId)),
                transactionId = TransactionId(transactionId),
                destinationPostingId = PostingId(postingId),
                destinationAccountId = AccountId(destinationAccountId),
                destinationCredit = Money.ofMinor(destinationCreditMinor, currency),
            )
        }.executeAsList()
        return when (targets.size) {
            0 -> Rg03MirrorBindingResult.Missing
            1 -> Rg03MirrorBindingResult.Unique(targets.single())
            else -> Rg03MirrorBindingResult.Ambiguous
        }
    }

    private fun commitManual(snapshot: Rg03ManualTransferSnapshot): Rg03ExecutionResult {
        return database.transactionWithResult {
            database.ledgerQueries.claimRg03OperationRequest(
                snapshot.ledgerId.value, snapshot.requestId.value, Rg03ActionType.MANUAL_ACCOUNT_TRANSFER.name,
            )
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveManual(snapshot)
            }
            validateManual(snapshot)?.let { rejected ->
                database.ledgerQueries.deleteRg03OperationRequest(snapshot.ledgerId.value, snapshot.requestId.value)
                return@transactionWithResult rejected
            }
            val ids = identitySource.transfer(snapshot.requestId)
            database.ledgerQueries.insertRg03ManualTransferSnapshot(
                snapshot.ledgerId.value, snapshot.requestId.value, snapshot.originalOccurredAtText,
                snapshot.sourceAccountId.value, snapshot.destinationAccountId.value,
                snapshot.sourceDebit.minorUnits, snapshot.destinationCredit.minorUnits,
                snapshot.fee.minorUnits, snapshot.sourceDebit.currency.code,
                snapshot.sourceDebit.currency.precision.toLong(), snapshot.feeCategoryId.value,
                EXPLICIT_MANUAL_SAVE,
            )
            val transfer = when (
                val created = createOwnAssetAccountTransfer(
                    catalog,
                    OwnAssetAccountTransferCommand(
                        snapshot.ledgerId, snapshot.sourceAccountId, snapshot.destinationAccountId,
                        snapshot.sourceDebit, snapshot.destinationCredit, snapshot.fee,
                        snapshot.feeCategoryId, TransactionTimes.collapsed(snapshot.occurredAt),
                    ),
                    ids.transferIds,
                )
            ) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteRg03OperationRequest(snapshot.ledgerId.value, snapshot.requestId.value)
                    return@transactionWithResult created.violation.toRg03Rejected()
                }
            }
            persistFormalTransaction(transfer.formalTransaction, snapshot.originalOccurredAtText)
            persistTransferSemantics(snapshot.ledgerId, transfer)
            database.ledgerQueries.insertRg03Confirmation(
                snapshot.ledgerId.value, ids.confirmationId.value, snapshot.requestId.value,
                null, transfer.formalTransaction.transaction.id.value, "MANUAL_TRANSFER",
            )
            database.ledgerQueries.insertRg03OperationReceipt(
                snapshot.ledgerId.value, snapshot.requestId.value, "ACCEPTED", ids.confirmationId.value,
                null, transfer.formalTransaction.transaction.id.value, null, null,
            )
            database.ledgerQueries.insertRg03PostingReconciliation(
                snapshot.ledgerId.value, ids.sourceReconciliationId,
                ids.transferIds.sourcePostingId.value, "PENDING",
            )
            database.ledgerQueries.insertRg03PostingReconciliation(
                snapshot.ledgerId.value, ids.destinationReconciliationId,
                ids.transferIds.destinationPostingId.value, "PENDING",
            )
            Rg03ExecutionResult.Accepted(
                listOf(
                    ReturnedId(ReturnedIdKind.CONFIRMATION, ids.confirmationId.value),
                    ReturnedId(ReturnedIdKind.TRANSACTION, transfer.formalTransaction.transaction.id.value),
                ),
            )
        }
    }

    private fun validateManual(snapshot: Rg03ManualTransferSnapshot): Rg03ExecutionResult.Rejected? {
        val validation = createOwnAssetAccountTransfer(
            catalog,
            OwnAssetAccountTransferCommand(
                snapshot.ledgerId, snapshot.sourceAccountId, snapshot.destinationAccountId,
                snapshot.sourceDebit, snapshot.destinationCredit, snapshot.fee,
                snapshot.feeCategoryId, TransactionTimes.collapsed(snapshot.occurredAt),
            ),
            SOURCE_VALIDATION_IDS,
        )
        return (validation as? DomainResult.Failure)?.violation?.toRg03Rejected()
    }

    private fun resolveManual(snapshot: Rg03ManualTransferSnapshot): Rg03ExecutionResult {
        val stored = database.ledgerQueries.selectRg03ManualCommit(
            snapshot.ledgerId.value, snapshot.requestId.value,
        ) { action, occurred, source, destination, sourceMinor, destinationMinor, feeMinor,
            currency, precision, category, marker, confirmation, transaction ->
            StoredManual(
                action, occurred, source, destination, sourceMinor, destinationMinor, feeMinor,
                currency, precision, category, marker, checkNotNull(confirmation), checkNotNull(transaction),
            )
        }.executeAsOneOrNull() ?: return Rg03ExecutionResult.RequestIdentityConflict
        return if (stored.matches(snapshot)) {
            Rg03ExecutionResult.NoChange(
                listOf(
                    ReturnedId(ReturnedIdKind.CONFIRMATION, stored.confirmationId),
                    ReturnedId(ReturnedIdKind.TRANSACTION, stored.transactionId),
                ),
            )
        } else {
            Rg03ExecutionResult.RequestIdentityConflict
        }
    }

    private fun commitSource(snapshot: Rg03SourceSnapshot): Rg03ExecutionResult {
        return database.transactionWithResult {
            database.ledgerQueries.claimRg03OperationRequest(
                snapshot.ledgerId.value, snapshot.requestId.value, Rg03ActionType.IMPORT_SOURCE_RECORD.name,
            )
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveSource(snapshot)
            }
            validateSource(snapshot)?.let { rejected ->
                database.ledgerQueries.deleteRg03OperationRequest(snapshot.ledgerId.value, snapshot.requestId.value)
                return@transactionWithResult rejected
            }
            val ids = identitySource.source(snapshot.requestId)
            val candidateId = ids.candidateId
            database.ledgerQueries.insertRg03SourceIntakeRequestSnapshot(
                snapshot.ledgerId.value, snapshot.requestId.value, snapshot.sourceId.value,
                snapshot.evidenceId.value, snapshot.originalObservedAtText, snapshot.sourceAccountId.value,
                snapshot.sourceDebit.minorUnits, snapshot.sourceDebit.currency.code,
                snapshot.sourceDebit.currency.precision.toLong(), snapshot.completeness.name,
                snapshot.feeCategoryId.value,
            )
            database.ledgerQueries.insertRg03SourceRecord(
                snapshot.ledgerId.value, snapshot.sourceId.value, snapshot.evidenceId.value,
                if (snapshot.completeness == SourceCompleteness.COMPLETE) {
                    "COMPLETE_TRANSFER_SOURCE"
                } else {
                    "INCOMPLETE_TRANSFER_SOURCE"
                },
                snapshot.originalObservedAtText, snapshot.sourceAccountId.value,
                snapshot.sourceDebit.minorUnits, snapshot.sourceDebit.currency.code,
                snapshot.sourceDebit.currency.precision.toLong(),
            )
            if (snapshot.completeness == SourceCompleteness.COMPLETE) {
                database.ledgerQueries.insertRg03CompleteSourceRequestSnapshot(
                    snapshot.ledgerId.value, snapshot.requestId.value,
                    checkNotNull(snapshot.destinationAccountId).value,
                    checkNotNull(snapshot.destinationCredit).minorUnits,
                    checkNotNull(snapshot.fee).minorUnits,
                )
                database.ledgerQueries.insertRg03CompleteSourceDetail(
                    snapshot.ledgerId.value, snapshot.sourceId.value,
                    checkNotNull(snapshot.destinationAccountId).value,
                    checkNotNull(snapshot.destinationCredit).minorUnits,
                    checkNotNull(snapshot.fee).minorUnits, snapshot.feeCategoryId.value,
                )
            }
            database.ledgerQueries.insertRg03Evidence(
                snapshot.ledgerId.value, snapshot.evidenceId.value, snapshot.sourceId.value,
                snapshot.originalObservedAtText, "SOURCE_DEBIT",
            )
            database.ledgerQueries.insertRg03Candidate(
                snapshot.ledgerId.value, candidateId.value, snapshot.sourceId.value,
                if (snapshot.completeness == SourceCompleteness.COMPLETE) "ACCOUNT_TRANSFER_WITH_FEE" else "ONE_SIDED_DEBIT",
                snapshot.feeCategoryId.value, "1.00", "complete_transfer_source", 1,
            )
            database.ledgerQueries.insertRg03CandidateStatus(
                snapshot.ledgerId.value, candidateId.value, 1, ids.candidateStatusId,
                "PENDING_CONFIRMATION", snapshot.requestId.value,
            )
            failureInjector.failAt(Rg03FailurePoint.SOURCE_AFTER_CANDIDATE)
            database.ledgerQueries.insertRg03OperationReceipt(
                snapshot.ledgerId.value, snapshot.requestId.value, "ACCEPTED", null,
                candidateId.value, null, snapshot.sourceId.value, snapshot.evidenceId.value,
            )
            Rg03ExecutionResult.Accepted(
                listOf(
                    ReturnedId(ReturnedIdKind.SOURCE, snapshot.sourceId.value),
                    ReturnedId(ReturnedIdKind.EVIDENCE, snapshot.evidenceId.value),
                    ReturnedId(ReturnedIdKind.CANDIDATE, candidateId.value),
                ),
            )
        }
    }

    private fun validateSource(snapshot: Rg03SourceSnapshot): Rg03ExecutionResult.Rejected? {
        val validation = if (snapshot.completeness == SourceCompleteness.COMPLETE) {
            val destination = snapshot.destinationAccountId
                ?: return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_INCOMPLETE)
            val destinationCredit = snapshot.destinationCredit
                ?: return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_INCOMPLETE)
            val fee = snapshot.fee
                ?: return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_INCOMPLETE)
            createOwnAssetAccountTransfer(
                catalog,
                OwnAssetAccountTransferCommand(
                    snapshot.ledgerId,
                    snapshot.sourceAccountId,
                    destination,
                    snapshot.sourceDebit,
                    destinationCredit,
                    fee,
                    snapshot.feeCategoryId,
                    TransactionTimes.collapsed(snapshot.observedAt),
                ),
                SOURCE_VALIDATION_IDS,
            )
        } else {
            validateIncompleteOwnAssetTransferSource(
                catalog,
                snapshot.ledgerId,
                snapshot.sourceAccountId,
                snapshot.sourceDebit,
                snapshot.feeCategoryId,
            )
        }
        val violation = (validation as? DomainResult.Failure)?.violation ?: return null
        return if (
            snapshot.completeness == SourceCompleteness.MISSING_DESTINATION &&
            violation == AccountTransferViolation.SameCurrencyRequired
        ) {
            Rg03ExecutionResult.Rejected(Rg03ExecutionError.SAME_CURRENCY_REQUIRED, "source_currency")
        } else {
            violation.toRg03Rejected()
        }
    }

    private fun commitCandidate(
        requestId: RequestId,
        candidate: Rg03PersistedTransferCandidate,
    ): Rg03ExecutionResult {
        val destination = candidate.destinationAccountId ?: return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_INCOMPLETE)
        val destinationCredit = candidate.destinationCredit ?: return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_INCOMPLETE)
        val fee = candidate.fee ?: return Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_INCOMPLETE)
        return database.transactionWithResult {
            database.ledgerQueries.claimRg03OperationRequest(
                candidate.ledgerId.value, requestId.value, Rg03ActionType.EXPLICIT_CANDIDATE_CONFIRMATION.name,
            )
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveConfirmation(candidate.ledgerId, requestId, candidate.candidateId)
            }
            val persisted = load(candidate.ledgerId, candidate.candidateId)
            if (persisted == null) {
                database.ledgerQueries.deleteRg03OperationRequest(candidate.ledgerId.value, requestId.value)
                return@transactionWithResult Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_FOUND)
            }
            if (persisted.status != CandidateStatus.PENDING_CONFIRMATION) {
                database.ledgerQueries.deleteRg03OperationRequest(candidate.ledgerId.value, requestId.value)
                return@transactionWithResult Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_PENDING)
            }
            if (persisted != candidate) {
                database.ledgerQueries.deleteRg03OperationRequest(candidate.ledgerId.value, requestId.value)
                return@transactionWithResult Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_FOUND)
            }
            val ids = identitySource.transfer(requestId)
            database.ledgerQueries.insertRg03CandidateConfirmationSnapshot(
                candidate.ledgerId.value, requestId.value, candidate.candidateId.value, 1,
            )
            val transfer = when (
                val created = createOwnAssetAccountTransfer(
                    catalog,
                    OwnAssetAccountTransferCommand(
                        candidate.ledgerId, candidate.sourceAccountId, destination,
                        candidate.sourceDebit, destinationCredit, fee,
                        candidate.feeCategoryId, TransactionTimes.collapsed(candidate.observedAt),
                    ),
                    ids.transferIds,
                )
            ) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteRg03OperationRequest(candidate.ledgerId.value, requestId.value)
                    return@transactionWithResult created.violation.toRg03Rejected()
                }
            }
            persistFormalTransaction(transfer.formalTransaction, candidate.originalObservedAtText)
            persistTransferSemantics(candidate.ledgerId, transfer)
            failureInjector.failAt(Rg03FailurePoint.CONFIRMATION_AFTER_FORMAL)
            database.ledgerQueries.updateRg03CandidateStatus(
                candidate.ledgerId.value, candidate.candidateId.value,
                checkNotNull(ids.candidateStatusId), "CONFIRMED", requestId.value,
            )
            database.ledgerQueries.insertRg03Confirmation(
                candidate.ledgerId.value, ids.confirmationId.value, requestId.value,
                candidate.candidateId.value, transfer.formalTransaction.transaction.id.value, "CANDIDATE_CONFIRMATION",
            )
            database.ledgerQueries.insertRg03OperationReceipt(
                candidate.ledgerId.value, requestId.value, "ACCEPTED", ids.confirmationId.value,
                candidate.candidateId.value, transfer.formalTransaction.transaction.id.value,
                candidate.sourceId.value, candidate.evidenceId.value,
            )
            database.ledgerQueries.insertRg03EvidenceLink(
                candidate.ledgerId.value, checkNotNull(ids.sourceEvidenceLinkId), candidate.evidenceId.value,
                ids.transferIds.sourcePostingId.value, "POSTING", "REAL_ACCOUNT_POSTING", "MATCHED",
            )
            database.ledgerQueries.insertRg03PostingReconciliation(
                candidate.ledgerId.value, ids.sourceReconciliationId,
                ids.transferIds.sourcePostingId.value, "MATCHED",
            )
            database.ledgerQueries.insertRg03PostingReconciliation(
                candidate.ledgerId.value, ids.destinationReconciliationId,
                ids.transferIds.destinationPostingId.value, "PENDING",
            )
            Rg03ExecutionResult.Accepted(
                listOf(
                    ReturnedId(ReturnedIdKind.CONFIRMATION, ids.confirmationId.value),
                    ReturnedId(ReturnedIdKind.TRANSACTION, transfer.formalTransaction.transaction.id.value),
                ),
            )
        }
    }

    private fun commitMirror(
        snapshot: Rg03MirrorSnapshot,
        target: Rg03MirrorTarget?,
    ): Rg03ExecutionResult = database.transactionWithResult {
        database.ledgerQueries.claimRg03OperationRequest(
            snapshot.ledgerId.value,
            snapshot.requestId.value,
            Rg03ActionType.IMPORT_MIRROR_RECORD.name,
        )
        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
            return@transactionWithResult resolveMirror(snapshot)
        }
        val current = target?.let {
            resolve(snapshot.ledgerId, Rg03MirrorScope(it.candidateId))
        } ?: Rg03MirrorBindingResult.Missing
        val persistedTarget = (current as? Rg03MirrorBindingResult.Unique)?.target
        if (persistedTarget == null) {
            database.ledgerQueries.deleteRg03OperationRequest(snapshot.ledgerId.value, snapshot.requestId.value)
            return@transactionWithResult Rg03ExecutionResult.Rejected(
                if (current == Rg03MirrorBindingResult.Ambiguous) {
                    Rg03ExecutionError.AMBIGUOUS_MIRROR_TARGET
                } else {
                    Rg03ExecutionError.MIRROR_TARGET_NOT_FOUND
                },
            )
        }
        if (
            (target != null && persistedTarget != target) ||
            snapshot.accountId != persistedTarget.destinationAccountId ||
            snapshot.credit != persistedTarget.destinationCredit
        ) {
            database.ledgerQueries.deleteRg03OperationRequest(snapshot.ledgerId.value, snapshot.requestId.value)
            return@transactionWithResult Rg03ExecutionResult.Rejected(Rg03ExecutionError.MIRROR_TARGET_MISMATCH)
        }
        val ids = identitySource.mirror(snapshot.requestId)
        val linkId = ids.evidenceLinkId
        database.ledgerQueries.insertRg03MirrorSnapshot(
            snapshot.ledgerId.value, snapshot.requestId.value, snapshot.sourceId.value,
            snapshot.evidenceId.value, snapshot.originalObservedAtText, snapshot.accountId.value,
            snapshot.credit.minorUnits, snapshot.credit.currency.code,
            snapshot.credit.currency.precision.toLong(),
        )
        database.ledgerQueries.insertRg03SourceRecord(
            snapshot.ledgerId.value, snapshot.sourceId.value, snapshot.evidenceId.value,
            "ACCOUNT_CREDIT_OBSERVATION", snapshot.originalObservedAtText, snapshot.accountId.value,
            snapshot.credit.minorUnits, snapshot.credit.currency.code,
            snapshot.credit.currency.precision.toLong(),
        )
        database.ledgerQueries.insertRg03Evidence(
            snapshot.ledgerId.value, snapshot.evidenceId.value, snapshot.sourceId.value,
            snapshot.originalObservedAtText, "DESTINATION_MIRROR",
        )
        database.ledgerQueries.insertRg03EvidenceLink(
            snapshot.ledgerId.value, linkId, snapshot.evidenceId.value,
            persistedTarget.destinationPostingId.value, "POSTING", "DESTINATION_ASSET_POSTING", "MATCHED",
        )
        failureInjector.failAt(Rg03FailurePoint.MIRROR_AFTER_LINK)
        database.ledgerQueries.insertRg03OperationReceipt(
            snapshot.ledgerId.value, snapshot.requestId.value, "ACCEPTED", null,
            persistedTarget.candidateId.value, persistedTarget.transactionId.value,
            snapshot.sourceId.value, snapshot.evidenceId.value,
        )
        database.ledgerQueries.updateRg03PostingReconciliationStatus(
            "MATCHED", snapshot.ledgerId.value, persistedTarget.destinationPostingId.value,
        )
        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
            "Mirror target reconciliation disappeared during commit"
        }
        Rg03ExecutionResult.Accepted(
            listOf(
                ReturnedId(ReturnedIdKind.SOURCE, snapshot.sourceId.value),
                ReturnedId(ReturnedIdKind.EVIDENCE, snapshot.evidenceId.value),
                ReturnedId(ReturnedIdKind.EVIDENCE_LINK, linkId),
            ),
        )
    }

    private fun resolveSource(snapshot: Rg03SourceSnapshot): Rg03ExecutionResult {
        val stored = database.ledgerQueries.selectRg03SourceCommit(
            snapshot.ledgerId.value,
            snapshot.requestId.value,
        ) { action, sourceId, evidenceId, observedAt, sourceAccountId, sourceDebitMinor,
                currencyCode, currencyPrecision, completeness, feeCategoryId,
                destinationAccountId, destinationCreditMinor, feeMinor, candidateId,
                receiptSourceId, receiptEvidenceId ->
            StoredSource(
                action, sourceId, evidenceId, observedAt, sourceAccountId, sourceDebitMinor,
                currencyCode, currencyPrecision, completeness, feeCategoryId,
                destinationAccountId, destinationCreditMinor, feeMinor,
                checkNotNull(candidateId), checkNotNull(receiptSourceId), checkNotNull(receiptEvidenceId),
            )
        }.executeAsOneOrNull() ?: return Rg03ExecutionResult.RequestIdentityConflict
        return if (stored.matches(snapshot)) {
            Rg03ExecutionResult.NoChange(
                listOf(
                    ReturnedId(ReturnedIdKind.SOURCE, stored.sourceId),
                    ReturnedId(ReturnedIdKind.EVIDENCE, stored.evidenceId),
                    ReturnedId(ReturnedIdKind.CANDIDATE, stored.candidateId),
                ),
            )
        } else {
            Rg03ExecutionResult.RequestIdentityConflict
        }
    }

    private fun resolveConfirmation(
        ledgerId: LedgerId,
        requestId: RequestId,
        candidateId: CandidateId,
    ): Rg03ExecutionResult {
        val stored = database.ledgerQueries.selectRg03ConfirmationCommit(
            ledgerId.value,
            requestId.value,
        ) { action, storedCandidateId, confirmed, confirmationId, receiptCandidateId,
                transactionId, _, _ ->
            StoredConfirmation(
                action, storedCandidateId, confirmed, checkNotNull(confirmationId),
                checkNotNull(receiptCandidateId), checkNotNull(transactionId),
            )
        }.executeAsOneOrNull() ?: return Rg03ExecutionResult.RequestIdentityConflict
        return if (stored.matches(candidateId)) {
            Rg03ExecutionResult.NoChange(
                listOf(
                    ReturnedId(ReturnedIdKind.CONFIRMATION, stored.confirmationId),
                    ReturnedId(ReturnedIdKind.TRANSACTION, stored.transactionId),
                ),
            )
        } else {
            Rg03ExecutionResult.RequestIdentityConflict
        }
    }

    private fun resolveMirror(snapshot: Rg03MirrorSnapshot): Rg03ExecutionResult {
        val stored = database.ledgerQueries.selectRg03MirrorCommit(
            snapshot.ledgerId.value,
            snapshot.requestId.value,
        ) { action, sourceId, evidenceId, observedAt, accountId, creditMinor,
                currencyCode, currencyPrecision, candidateId, transactionId,
                receiptSourceId, receiptEvidenceId, linkId, postingId ->
            StoredMirror(
                action, sourceId, evidenceId, observedAt, accountId, creditMinor,
                currencyCode, currencyPrecision, checkNotNull(candidateId),
                checkNotNull(transactionId), checkNotNull(receiptSourceId),
                checkNotNull(receiptEvidenceId), linkId, postingId,
            )
        }.executeAsOneOrNull() ?: return Rg03ExecutionResult.RequestIdentityConflict
        return if (stored.matches(snapshot)) {
            Rg03ExecutionResult.NoChange(
                listOf(
                    ReturnedId(ReturnedIdKind.SOURCE, stored.sourceId),
                    ReturnedId(ReturnedIdKind.EVIDENCE, stored.evidenceId),
                    ReturnedId(ReturnedIdKind.EVIDENCE_LINK, stored.linkId),
                ),
            )
        } else {
            Rg03ExecutionResult.RequestIdentityConflict
        }
    }

    private fun persistFormalTransaction(value: FormalTransaction, originalTimeText: String) {
        value.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, value.transaction.ledgerId.value) }
        database.ledgerQueries.insertTransaction(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.kind.name)
        value.versions.forEach { version ->
            database.ledgerQueries.insertTransactionVersion(
                version.id.value, version.transactionId.value, value.transaction.ledgerId.value,
                version.versionNumber.toLong(), version.postingSetId.value,
                originalTimeText, originalTimeText, originalTimeText, version.note,
            )
        }
        database.ledgerQueries.insertTransactionCurrentVersion(
            value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.currentVersionId.value,
        )
        value.postingSets.forEach { set ->
            set.postings.forEachIndexed { index, posting ->
                database.ledgerQueries.insertPosting(
                    posting.id.value, set.id.value, value.transaction.ledgerId.value, index.toLong(),
                    posting.accountId.value, posting.amount.minorUnits, posting.amount.currency.code,
                    posting.amount.currency.precision.toLong(),
                )
            }
        }
    }

    private fun persistTransferSemantics(ledgerId: LedgerId, transfer: AccountTransfer) {
        transfer.postings.forEach { typed ->
            database.ledgerQueries.insertRg03TransferPostingSemantic(
                ledgerId.value,
                typed.posting.id.value,
                when (typed.role) {
                    TransferPostingRole.PRINCIPAL_OUT -> "TRANSFER_PRINCIPAL_OUT"
                    TransferPostingRole.PRINCIPAL_IN -> "TRANSFER_PRINCIPAL_IN"
                    TransferPostingRole.FEE -> "TRANSFER_FEE"
                },
                typed.categoryId?.value,
                if (typed.role == TransferPostingRole.FEE) 0 else 1,
            )
        }
    }

}

private const val EXPLICIT_MANUAL_SAVE = "explicit_manual_save"

private data class StoredManual(
    val action: String,
    val occurredAt: String,
    val sourceAccountId: String,
    val destinationAccountId: String,
    val sourceDebitMinor: Long,
    val destinationCreditMinor: Long,
    val feeMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val feeCategoryId: String,
    val confirmationMarker: String,
    val confirmationId: String,
    val transactionId: String,
) {
    fun matches(snapshot: Rg03ManualTransferSnapshot): Boolean =
        action == Rg03ActionType.MANUAL_ACCOUNT_TRANSFER.name &&
            occurredAt == snapshot.originalOccurredAtText && Instant.parse(occurredAt) == snapshot.occurredAt &&
            sourceAccountId == snapshot.sourceAccountId.value &&
            destinationAccountId == snapshot.destinationAccountId.value &&
            sourceDebitMinor == snapshot.sourceDebit.minorUnits &&
            destinationCreditMinor == snapshot.destinationCredit.minorUnits &&
            feeMinor == snapshot.fee.minorUnits &&
            currencyCode == snapshot.sourceDebit.currency.code &&
            currencyPrecision == snapshot.sourceDebit.currency.precision.toLong() &&
            feeCategoryId == snapshot.feeCategoryId.value &&
            confirmationMarker == EXPLICIT_MANUAL_SAVE
}

private data class StoredSource(
    val action: String,
    val sourceId: String,
    val evidenceId: String,
    val observedAt: String,
    val sourceAccountId: String,
    val sourceDebitMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val completeness: String,
    val feeCategoryId: String,
    val destinationAccountId: String?,
    val destinationCreditMinor: Long?,
    val feeMinor: Long?,
    val candidateId: String,
    val receiptSourceId: String,
    val receiptEvidenceId: String,
) {
    fun matches(snapshot: Rg03SourceSnapshot): Boolean =
        action == Rg03ActionType.IMPORT_SOURCE_RECORD.name &&
            sourceId == snapshot.sourceId.value &&
            evidenceId == snapshot.evidenceId.value &&
            observedAt == snapshot.originalObservedAtText && Instant.parse(observedAt) == snapshot.observedAt &&
            sourceAccountId == snapshot.sourceAccountId.value &&
            sourceDebitMinor == snapshot.sourceDebit.minorUnits &&
            currencyCode == snapshot.sourceDebit.currency.code &&
            currencyPrecision == snapshot.sourceDebit.currency.precision.toLong() &&
            completeness == snapshot.completeness.name &&
            feeCategoryId == snapshot.feeCategoryId.value &&
            destinationAccountId == snapshot.destinationAccountId?.value &&
            destinationCreditMinor == snapshot.destinationCredit?.minorUnits &&
            feeMinor == snapshot.fee?.minorUnits &&
            receiptSourceId == sourceId &&
            receiptEvidenceId == evidenceId
}

private data class StoredConfirmation(
    val action: String,
    val candidateId: String,
    val confirmed: Long,
    val confirmationId: String,
    val receiptCandidateId: String,
    val transactionId: String,
) {
    fun matches(expectedCandidateId: CandidateId): Boolean =
        action == Rg03ActionType.EXPLICIT_CANDIDATE_CONFIRMATION.name &&
            candidateId == expectedCandidateId.value &&
            confirmed == 1L &&
            receiptCandidateId == candidateId
}

private data class StoredMirror(
    val action: String,
    val sourceId: String,
    val evidenceId: String,
    val observedAt: String,
    val accountId: String,
    val creditMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val candidateId: String,
    val transactionId: String,
    val receiptSourceId: String,
    val receiptEvidenceId: String,
    val linkId: String,
    val postingId: String,
) {
    fun matches(snapshot: Rg03MirrorSnapshot): Boolean =
        action == Rg03ActionType.IMPORT_MIRROR_RECORD.name &&
            sourceId == snapshot.sourceId.value &&
            evidenceId == snapshot.evidenceId.value &&
            observedAt == snapshot.originalObservedAtText && Instant.parse(observedAt) == snapshot.observedAt &&
            accountId == snapshot.accountId.value &&
            creditMinor == snapshot.credit.minorUnits &&
            currencyCode == snapshot.credit.currency.code &&
            currencyPrecision == snapshot.credit.currency.precision.toLong() &&
            receiptSourceId == sourceId &&
            receiptEvidenceId == evidenceId
}

private fun DomainViolation.toRg03Rejected(): Rg03ExecutionResult.Rejected = when (this) {
    is AccountTransferViolation.KnownAccountRequired -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.KNOWN_ACCOUNT_REQUIRED,
        field.inputField(),
    )
    AccountTransferViolation.DistinctAccountsRequired -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.DISTINCT_OWN_REAL_FINANCIAL_ACCOUNTS_REQUIRED,
        "destination_account_id",
    )
    is AccountTransferViolation.OwnAccountRequired -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.OWN_ACCOUNT_REQUIRED,
        field.inputField(),
    )
    is AccountTransferViolation.RealFinancialAccountRequired -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.REAL_FINANCIAL_ACCOUNT_REQUIRED,
        field.inputField(),
    )
    is AccountTransferViolation.AmountMustBePositive -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.MUST_BE_POSITIVE,
        field.inputField(),
    )
    AccountTransferViolation.AmountsMustBalance -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.AMOUNTS_MUST_BALANCE,
        "fee_amount",
    )
    AccountTransferViolation.SameCurrencyRequired -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.SAME_CURRENCY_REQUIRED,
        "destination_currency",
    )
    AccountTransferViolation.FeeMustNotBeNegative -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.FEE_MUST_NOT_BE_NEGATIVE,
        "fee_amount",
    )
    AccountTransferViolation.InvalidFeeCategory -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.INVALID_FEE_CATEGORY,
        "fee_category_id",
    )
    is AccountTransferViolation.AssetAccountRequired -> Rg03ExecutionResult.Rejected(
        Rg03ExecutionError.ASSET_ACCOUNT_REQUIRED,
        field.inputField(),
    )
    DomainViolation.ArithmeticOverflow,
    is DomainViolation.AmountNotRepresentableInCurrency,
    DomainViolation.InvalidPostingSet,
    DomainViolation.UnbalancedPostingSet,
    DomainViolation.InvalidFormalTransaction,
    DomainViolation.InvalidCatalog,
    DomainViolation.InvalidOrdinaryExpense,
    DomainViolation.InvalidOrdinaryIncome,
    DomainViolation.InvalidBalanceReplay,
    DomainViolation.InvalidMixedPayment,
    DomainViolation.InvalidMergedPayment,
    DomainViolation.InvalidRefundReceipt,
    is OrdinaryExpenseViolation,
    is OrdinaryIncomeViolation,
    is MixedPaymentViolation,
    is MergedPaymentViolation,
    is BalanceAdjustmentViolation,
    is PrincipalTransferViolation,
    is StoredValueViolation,
    is LendingViolation,
    is PeriodicAllocationViolation,
    is ExplicitOperationConfirmationViolation,
    is CorrectTransactionVersionViolation,
    is ReconciliationMatchViolation,
    is PostingReplacementViolation,
    is PostingReconciliationViolation,
    is CategoryRenameViolation -> Rg03ExecutionResult.Rejected(Rg03ExecutionError.DOMAIN_VALIDATION_FAILED)
}

private fun AccountTransferField.inputField(): String = when (this) {
    AccountTransferField.SOURCE_ACCOUNT -> "source_account_id"
    AccountTransferField.DESTINATION_ACCOUNT -> "destination_account_id"
    AccountTransferField.SOURCE_DEBIT -> "source_debit_amount"
    AccountTransferField.DESTINATION_CREDIT -> "destination_credit_amount"
    AccountTransferField.FEE -> "fee_amount"
}

private val SOURCE_VALIDATION_IDS = AccountTransferIds(
    TransactionId("validation-transaction"),
    TransactionVersionId("validation-version"),
    PostingSetId("validation-posting-set"),
    PostingId("validation-source-posting"),
    PostingId("validation-destination-posting"),
    PostingId("validation-fee-posting"),
)
