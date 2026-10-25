package com.unifiedledger.data

import com.unifiedledger.application.CatalogAdmissionReader
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.QueryRecycleBin
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RecycleBinRow
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-05 transfer restore end-to-end evidence (transfer spec sections 4.4/8.2, D-228 slice 3;
 * acceptance vector T-09): a voided manual transfer restores through the unchanged void/restore
 * port with the slice-3 transfer-aware revalidation - value-identical restoration, the DP-9
 * rename-but-active clarification, and typed zero-write rejections (transaction stays voided)
 * for a deactivated endpoint or fee category. The recycle-bin projection flips with it: a
 * voided transfer's restore judgment is no longer the kind-gate rejection but the catalog
 * admissibility the revalidation actually reports. Values are anonymous synthetic.
 */
class P705TransferRestorePathTest {
    private val ledgerId = P705Fixture.ledgerId
    private val catalog = P705Fixture.catalog()

    /** Seeds one manual ACCOUNT_TRANSFER chain in the creation factory's frozen leg order. */
    private fun insertManualTransfer(
        harness: P705Database,
        transactionId: String,
        sourceAccount: AccountId = P705Fixture.bankA,
        destinationAccount: AccountId = P705Fixture.bankB,
        destinationCreditMinor: Long,
        feeMinor: Long,
    ) {
        val postingSetId = "$transactionId-posting-set-1"
        val versionId = "$transactionId-version-1"
        harness.database.ledgerQueries.insertTransaction(transactionId, ledgerId.value, TransactionKind.ACCOUNT_TRANSFER.name)
        harness.database.ledgerQueries.insertPostingSet(postingSetId, ledgerId.value)
        harness.database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            ledgerId.value,
            1L,
            postingSetId,
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            "rent",
        )
        val legs =
            buildList {
                add(sourceAccount to -(destinationCreditMinor + feeMinor))
                add(destinationAccount to destinationCreditMinor)
                if (feeMinor > 0L) add(P705Fixture.expenseAccount to feeMinor)
            }
        legs.forEachIndexed { index, (accountId, amount) ->
            harness.database.ledgerQueries.insertPosting(
                "$transactionId-posting-$index",
                postingSetId,
                ledgerId.value,
                index.toLong(),
                accountId.value,
                amount,
                P705Fixture.cny.code,
                P705Fixture.cny.precision.toLong(),
            )
        }
        harness.database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId.value, versionId)
    }

    private fun void(
        harness: P705Database,
        ids: P705Ids,
        requestId: RequestId,
        transactionId: String,
    ): VoidTransactionResult =
        ExecuteVoidTransaction(harness.voidPort, ids.voidSource, fixedClock(P705Fixture.voidedAt))
            .execute(
                VoidTransactionRequest(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    transactionId = TransactionId(transactionId),
                    reason = VoidReason(VoidReasonCode.MIS_ENTERED, "wrong account"),
                    confirmation = ExplicitManualSave,
                ),
            )

    private fun restore(
        harness: P705Database,
        ids: P705Ids,
        requestId: RequestId,
        transactionId: String,
        admissionReader: CatalogAdmissionReader = P705Fixture.admissionReader,
    ): VoidTransactionResult =
        ExecuteRestoreTransaction(
            harness.voidPort,
            ids.voidSource,
            fixedClock(Instant.parse("2026-05-01T01:00:00Z")),
            admissionReader,
        ).execute(
            VoidTransactionRequest(
                ledgerId = ledgerId,
                requestId = requestId,
                transactionId = TransactionId(transactionId),
                reason = VoidReason(VoidReasonCode.VOIDED_IN_ERROR, "restore"),
                confirmation = ExplicitManualSave,
            ),
        )

    private fun catalogWith(
        accounts: List<Account> = catalog.accounts,
    ): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(accounts, catalog.categories)).value

    private fun recycleBinRow(
        harness: P705Database,
        projectionCatalog: LedgerCatalog = catalog,
    ): RecycleBinRow {
        val result = QueryRecycleBin(harness.readAdapter, ledgerId, projectionCatalog).query()
        return assertIs<RecycleBinResult.Success>(result).rows.single()
    }

    @Test
    fun aVoidedTransferRestoresValueIdentically() {
        P705Database.create("p705-t-restore-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-restore", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = P705Ids("p705-t-restore")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), "tx-transfer-restore"))
            assertEquals(0, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)

            val restored = assertIs<VoidTransactionResult.Created>(restore(harness, ids, ids.requestId(), "tx-transfer-restore"))
            // The transfer is effective again with its original three legs byte for byte - no
            // version was appended, no posting rebound, nothing compensated.
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            assertEquals(1L, harness.versionCount("tx-transfer-restore"))
            assertEquals(
                listOf(P705Fixture.bankA.value to -10_200L, P705Fixture.bankB.value to 10_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-restore"),
            )
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(
                listOf("void", "restore"),
                harness.database.ledgerQueries
                    .transactionVoidFactsForTransaction(ledgerId.value, "tx-transfer-restore") { _, factKind, _, _, _, _, _ ->
                        factKind
                    }.executeAsList(),
            )
            // T-09's recycle-bin side: with an admissible catalog the voided row was restorable.
            assertEquals(null, recycleBinRow(harness).restoreRejectionCode)
            assertEquals(TransactionVoidFactKind.RESTORE, restored.receipt.factKind)
        }
    }

    @Test
    fun renamedButActiveEndpointsRemainAdmissibleForARestore() {
        P705Database.create("p705-t-restore-rename-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-rename", destinationCreditMinor = 10_000L, feeMinor = 0L)
            val ids = P705Ids("p705-t-restore-rename")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), "tx-transfer-rename"))

            val renamed =
                catalogWith(
                    accounts =
                        catalog.accounts.map { if (it.id == P705Fixture.bankA) it.copy(name = "银行A（新名）") else it },
                )
            assertIs<VoidTransactionResult.Created>(
                restore(harness, ids, ids.requestId(), "tx-transfer-rename", admissionReader = CatalogAdmissionReader { renamed }),
            )
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
        }
    }

    @Test
    fun aDeactivatedEndpointBlocksTheRestoreAndTheTransactionStaysVoided() {
        P705Database.create("p705-t-restore-deactive-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-deactive", destinationCreditMinor = 10_000L, feeMinor = 0L)
            val ids = P705Ids("p705-t-restore-deactive")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), "tx-transfer-deactive"))

            val deactivated =
                catalogWith(
                    accounts =
                        catalog.accounts.map { if (it.id == P705Fixture.bankB) it.copy(active = false) else it },
                )
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE),
                restore(harness, ids, ids.requestId(), "tx-transfer-deactive", admissionReader = CatalogAdmissionReader { deactivated }),
            )
            // Zero-write rejection (T-09): the transaction stays voided, one fact only, no
            // restore request survived, and the recycle bin reports the blocking code.
            assertEquals(0, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_request"))
            assertEquals(1L, harness.versionCount("tx-transfer-deactive"))
            assertEquals(
                P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
                recycleBinRow(harness, projectionCatalog = deactivated).restoreRejectionCode,
            )
        }
    }

    @Test
    fun aDeactivatedFeeCategoryBlocksTheRestoreOfAThreeLegTransfer() {
        P705Database.create("p705-t-restore-fee-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = P705Ids("p705-t-restore-fee")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), "tx-transfer-fee"))

            val deactivatedCategory =
                assertIs<DomainResult.Success<LedgerCatalog>>(
                    LedgerCatalog.create(
                        catalog.accounts,
                        catalog.categories.map { if (it.id == P705Fixture.food) it.copy(active = false) else it },
                    ),
                ).value
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE),
                restore(harness, ids, ids.requestId(), "tx-transfer-fee", admissionReader = CatalogAdmissionReader { deactivatedCategory }),
            )
            assertEquals(0, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_request"))
        }
    }
}
