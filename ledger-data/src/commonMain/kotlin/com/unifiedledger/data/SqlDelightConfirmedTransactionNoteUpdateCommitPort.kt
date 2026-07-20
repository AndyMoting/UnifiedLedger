package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateCommitPort
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateReceipt
import com.unifiedledger.application.ConfirmedTransactionNoteUpdateResult
import com.unifiedledger.application.TransactionNoteUpdateRequestIdentity
import com.unifiedledger.application.TransactionNoteUpdateRequestSnapshot
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId

class SqlDelightConfirmedTransactionNoteUpdateCommitPort private constructor(
    private val database: LedgerDatabase,
) : ConfirmedTransactionNoteUpdateCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        driver.execute(null, "PRAGMA busy_timeout = 5000", 0)
    }

    override fun commitOnce(
        identity: TransactionNoteUpdateRequestIdentity,
        requestSnapshot: TransactionNoteUpdateRequestSnapshot,
        replaceNote: () -> ConfirmedTransactionNoteUpdateResult,
    ): ConfirmedTransactionNoteUpdateResult {
        require(identity.ledgerId == requestSnapshot.ledgerId)
        return database.transactionWithResult {
            database.ledgerQueries.claimTransactionNoteUpdateRequest(
                identity.ledgerId.value, identity.requestId.value, requestSnapshot.transactionId.value,
                requestSnapshot.command.note, NOTE_UPDATE_CONFIRMATION_MARKER,
            )
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                return@transactionWithResult resolveExisting(identity, requestSnapshot)
            }
            when (val created = replaceNote()) {
                !is ConfirmedTransactionNoteUpdateResult.Created -> {
                    database.ledgerQueries.deleteTransactionNoteUpdateRequest(identity.ledgerId.value, identity.requestId.value)
                    created
                }
                is ConfirmedTransactionNoteUpdateResult.Created -> {
                    val receipt = created.receipt
                    val copied = database.ledgerQueries.copyCurrentVersionWithNewNote(
                        receipt.versionId.value, requestSnapshot.command.note, requestSnapshot.transactionId.value,
                        identity.ledgerId.value, expected_current_version_id = receipt.expectedCurrentVersionId.value,
                    )
                    if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                        database.ledgerQueries.deleteTransactionNoteUpdateRequest(identity.ledgerId.value, identity.requestId.value)
                        return@transactionWithResult ConfirmedTransactionNoteUpdateResult.StaleCurrentVersion
                    }
                    database.ledgerQueries.compareAndSetCurrentVersion(
                        receipt.versionId.value, requestSnapshot.transactionId.value, identity.ledgerId.value,
                        receipt.expectedCurrentVersionId.value,
                    )
                    check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L)
                    database.ledgerQueries.insertConfirmedTransactionNoteUpdateReceipt(
                        identity.ledgerId.value, identity.requestId.value, receipt.confirmationId.value,
                        receipt.transactionId.value, receipt.versionId.value, receipt.expectedCurrentVersionId.value,
                    )
                    created
                }
            }
        }
    }

    private fun resolveExisting(
        identity: TransactionNoteUpdateRequestIdentity,
        snapshot: TransactionNoteUpdateRequestSnapshot,
    ): ConfirmedTransactionNoteUpdateResult {
        val existing = checkNotNull(database.ledgerQueries.selectCommittedTransactionNoteUpdateRequest(
            identity.ledgerId.value, identity.requestId.value,
        ) { transactionId, note, marker, confirmationId, versionId, expectedCurrentVersionId ->
            StoredNoteUpdate(transactionId, note, marker, confirmationId, versionId, expectedCurrentVersionId)
        }.executeAsOneOrNull()) { "Committed note update is missing its receipt" }
        return if (existing.matches(snapshot)) {
            ConfirmedTransactionNoteUpdateResult.NoChange(
                ConfirmedTransactionNoteUpdateReceipt(
                    ConfirmationId(existing.confirmationId), TransactionId(existing.transactionId),
                    TransactionVersionId(existing.versionId), TransactionVersionId(existing.expectedCurrentVersionId),
                ),
            )
        } else {
            ConfirmedTransactionNoteUpdateResult.RequestIdentityConflict(identity)
        }
    }
}

private data class StoredNoteUpdate(
    val transactionId: String,
    val note: String,
    val confirmationMarker: String,
    val confirmationId: String,
    val versionId: String,
    val expectedCurrentVersionId: String,
) {
    fun matches(snapshot: TransactionNoteUpdateRequestSnapshot): Boolean =
        transactionId == snapshot.transactionId.value && note == snapshot.command.note &&
            confirmationMarker == NOTE_UPDATE_CONFIRMATION_MARKER
}

private const val NOTE_UPDATE_CONFIRMATION_MARKER = "explicit_manual_save"
