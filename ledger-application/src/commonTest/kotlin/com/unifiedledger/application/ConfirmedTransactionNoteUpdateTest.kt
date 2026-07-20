package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionNoteUpdateCommand
import com.unifiedledger.domain.TransactionNoteUpdateIds
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConfirmedTransactionNoteUpdateTest {
    @Test
    fun explicitlyConfirmedNoteUpdateUsesDedicatedRequestSnapshotAndReturnsReceipt() {
        val port = RecordingNoteUpdatePort()
        val execute = ExecuteConfirmedTransactionNoteUpdate(port) {
            ConfirmedTransactionNoteUpdateIds(
                ConfirmationId("confirmation-note"),
                TransactionNoteUpdateIds(TransactionVersionId("version-expense-rg01-v2")),
                TransactionVersionId("version-expense-rg01-v1"),
            )
        }

        val result = execute.execute(
            ExplicitlyConfirmedTransactionNoteUpdate(
                LedgerId("ledger-a"), RequestId("request-rg01-note-update"),
                TransactionId("tx-expense-rg01"), "早餐", ExplicitManualSave,
            ),
        )

        assertEquals(
            ConfirmedTransactionNoteUpdateResult.Created(
                ConfirmedTransactionNoteUpdateReceipt(
                    ConfirmationId("confirmation-note"), TransactionId("tx-expense-rg01"),
                    TransactionVersionId("version-expense-rg01-v2"), TransactionVersionId("version-expense-rg01-v1"),
                ),
            ),
            result,
        )
        assertEquals(TransactionNoteUpdateCommand("早餐"), port.snapshot.command)
    }
}

private class RecordingNoteUpdatePort : ConfirmedTransactionNoteUpdateCommitPort {
    lateinit var snapshot: TransactionNoteUpdateRequestSnapshot
    override fun commitOnce(
        identity: TransactionNoteUpdateRequestIdentity,
        requestSnapshot: TransactionNoteUpdateRequestSnapshot,
        replaceNote: () -> ConfirmedTransactionNoteUpdateResult,
    ): ConfirmedTransactionNoteUpdateResult {
        snapshot = requestSnapshot
        return replaceNote()
    }
}
