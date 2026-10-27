package com.unifiedledger.data

import com.unifiedledger.application.CatalogAdmissionReader
import com.unifiedledger.application.CatalogItemIdSource
import com.unifiedledger.application.CatalogItemReferenceProbe
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteTagMerchantCommand
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.MerchantAccumulationReadResult
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.MonthlyContributionReadResult
import com.unifiedledger.application.QueryRecycleBin
import com.unifiedledger.application.QueryWidgetMonthlySnapshot
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RecycleBinRow
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantRequestId
import com.unifiedledger.application.TagMerchantRequestIdSource
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.application.WidgetMonthlySnapshotStatus
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
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
    fun aRestoredTransferCannotBeRestoredAgainNorRevoidedBeyondTheCycle() {
        P705Database.create("p705-t-restore-cycle-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-cycle", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = P705Ids("p705-t-restore-cycle")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), "tx-transfer-cycle"))
            assertIs<VoidTransactionResult.Created>(restore(harness, ids, ids.requestId(), "tx-transfer-cycle"))

            // T-08 on the transfer: restore happens exactly once. A second restore is a typed
            // rejection with zero writes - the transaction is effective, not voided, so there is
            // nothing to restore.
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_TRANSACTION_NOT_VOIDED),
                restore(harness, ids, ids.requestId(), "tx-transfer-cycle"),
            )
            assertEquals(1L, harness.versionCount("tx-transfer-cycle"))
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)

            // T-10 on the transfer: the cycle is exhausted. After one void and one restore, a
            // second void is a typed rejection rather than a third fact. T-10's other clause -
            // a sequence gap, a repeated kind, an update or a delete aborting at the store - is
            // storage-level and kind-independent, so it is covered once by
            // `P705VoidRestoreCommitPortTest.databaseGuardsRejectSequenceGapsNonAlternatingFactsAndHistoryRewrites`
            // (`P705VoidRestoreCommitPortTest.kt:461-495`) over the trigger definitions in
            // `Ledger.sq:10253`, `:10258`, `:10270` and `:10272`; it is not repeated here.
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_VOID_CYCLE_EXHAUSTED),
                void(harness, ids, ids.requestId(), "tx-transfer-cycle"),
            )
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(1L, harness.versionCount("tx-transfer-cycle"))
            // Still effective, still value-identical: the rejected attempts wrote nothing.
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            assertEquals(
                listOf(P705Fixture.bankA.value to -10_200L, P705Fixture.bankB.value to 10_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-cycle"),
            )
        }
    }

    @Test
    fun aVoidedTransferRestoresValueIdentically() {
        P705Database.create("p705-t-restore-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-restore", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = P705Ids("p705-t-restore")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), "tx-transfer-restore"))
            assertEquals(0, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            // T-06's recycle-bin face, stated explicitly rather than only implied by `single()`:
            // the voided transfer IS listed in the bin, with its kind and the recorded reason.
            // The other effective surfaces are asserted in P705TransferCorrectionCommitPortTest's
            // voided-transfer vector, not here.
            val binned = recycleBinRow(harness)
            assertEquals(TransactionId("tx-transfer-restore"), binned.voided.transactionId)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, binned.voided.kind)
            assertEquals(VoidReasonCode.MIS_ENTERED, binned.voided.voidReason.code)
            assertEquals(3, binned.voided.postings.size)
            // T-09's recycle-bin side, on the voided state the bin projects: the transfer-aware
            // revalidation admits the three legs (endpoints active, fee category active), so the
            // restore judgment is admissible - under the replaced EXPENSE/INCOME hardcode this
            // row carried P705_KIND_NOT_SUPPORTED.
            assertEquals(null, binned.restoreRejectionCode)

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
            // The restored transaction correctly leaves the voided-only projection: the bin has
            // zero rows after a successful restore (it never lists effective transactions).
            assertEquals(
                emptyList<RecycleBinRow>(),
                assertIs<RecycleBinResult.Success>(QueryRecycleBin(harness.readAdapter, ledgerId, catalog).query()).rows,
            )
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

/**
 * P7-05 T-06 transfer-void derived-read evidence (transfer spec section 7.2 `:182-202`, sections
 * 7.3/10 `:274`; D-228 J1): a voided three-leg manual `ACCOUNT_TRANSFER` leaves the
 * monthly-contribution read (N2), the shared monthly widget snapshot (N3), the tag/merchant
 * filter page (N4) and the merchant accumulation rows (N5).
 *
 * Every read is driven through the real SQL adapters over the file-backed fixture, and each
 * vector carries a non-degenerate baseline (the transaction is visible on the same read before
 * the void), so an always-empty read can never pass. The seeds use the manual transfer chain
 * [P705Database.insertManualTransfer], never the import-created chain: the void port rejects an
 * import creation lineage with `P705_CREATION_LINEAGE_NOT_SUPPORTED`.
 */
class P705TransferVoidDerivedSurfaceTest {
    private val ledgerId = P705Fixture.ledgerId
    private val march = YearMonth(2026, 3)
    private val marchStart = MonthlyBuckets.monthStart(march)
    private val aprilStart = MonthlyBuckets.monthStart(march.plus(1, DateTimeUnit.MONTH))

    /** The real void port over the seeded manual transfer (no import lineage, so no lineage gate). */
    private fun voidTransfer(
        harness: P705Database,
        transactionId: String,
    ): VoidTransactionResult {
        val ids = P705Ids("p705-t06-void-$transactionId")
        return ExecuteVoidTransaction(harness.voidPort, ids.voidSource, fixedClock(P705Fixture.voidedAt))
            .execute(
                VoidTransactionRequest(
                    ledgerId = ledgerId,
                    requestId = ids.requestId(),
                    transactionId = TransactionId(transactionId),
                    reason = VoidReason(VoidReasonCode.MIS_ENTERED, "wrong transfer"),
                    confirmation = ExplicitManualSave,
                ),
            )
    }

    /**
     * N2 and N3 (spec section 7.2 `:187`/`:189`): the bounded monthly-contribution port and the
     * monthly widget snapshot. N3 is the SAME port reused: `QueryWidgetMonthlySnapshot` performs
     * one `MonthlyContributionReadPort.readContributions` over the same `[monthStart,
     * nextMonthStart)` window and one `MonthlyBuckets.aggregate`, and the repo exposes no
     * independent widget read face — `widgetAmounts`/`isCatalogConsistent` are private
     * (`WidgetMonthlySnapshot.kt:223-269`) — so N3 is asserted on the N2 port with this note
     * rather than claimed as a separate derived surface.
     */
    @Test
    fun voidingAManualTransferRemovesItFromTheMonthlyContributionAndWidgetFaces() {
        P705Database.create("p705-t06-monthly-").use { harness ->
            val transactionId = "tx-transfer-monthly"
            harness.insertManualTransfer(transactionId)
            val catalogStore = harness.transferCatalogStore()
            val catalogVersion = harness.bootstrapTransferCatalog().catalogVersion
            // The bootstrap's only public version write path seeds exactly 1; a different value
            // would mean the fixture seeded a second catalog generation the read must gate on.
            assertEquals(1L, catalogVersion)
            val adapter = SqlDelightMonthlyContributionReadAdapter(harness.database, catalogStore)

            fun monthTransactionIds(): List<String> =
                assertIs<MonthlyContributionReadResult.Success>(
                    adapter.readContributions(ledgerId, marchStart, aprilStart, catalogVersion),
                ).rows.map { it.transactionId.value }

            // Non-degenerate baseline: the FEE leg is an ordinary March expense and the transfer
            // is visible on both the port and the widget before the void.
            assertEquals(listOf(transactionId), monthTransactionIds())
            val widget = QueryWidgetMonthlySnapshot(adapter, fixedClock(Instant.parse("2026-03-20T05:00:00Z")))
            val before = widget.read(ledgerId, march, catalogVersion, generation = 1)
            assertEquals(WidgetMonthlySnapshotStatus.AVAILABLE, before.status)
            assertEquals(200L, requireNotNull(before.amounts).netExpenseMinorUnits)

            assertIs<VoidTransactionResult.Created>(voidTransfer(harness, transactionId))

            // N2: after the void the March window holds no effective row. The read still
            // SUCCEEDS — an empty month is a real zero, never a failed read.
            assertEquals(emptyList<String>(), monthTransactionIds())
            // N3: the widget over the same port reports the same real zero, still AVAILABLE.
            val after = widget.read(ledgerId, march, catalogVersion, generation = 1)
            assertEquals(WidgetMonthlySnapshotStatus.AVAILABLE, after.status)
            assertEquals(0L, requireNotNull(after.amounts).netExpenseMinorUnits)
            assertEquals(0L, requireNotNull(after.amounts).ordinaryIncomeMinorUnits)
        }
    }

    /**
     * N4 and N5 (spec section 7.2 `:190`/`:191`): the bounded tag/merchant filter page and the
     * merchant accumulation window. The tag and merchant are created through the REAL catalog
     * command chain (two accepted commands, each advancing the dedicated `catalog_item_version`),
     * and the generation the reads gate on is loaded AFTER both commands from the same store —
     * never `+1`, never the pre-command value, never the account catalog's `catalog_version`.
     *
     * The three annotation counts are explicit GATE assertions, not per-row targets: a void never
     * deletes the annotation aggregate (N6-N8 history faces), so no single-column ablation is
     * expected to flip them; they prove the annotation was really seeded and survived the void.
     * The load-bearing predicate is the effective filter inside the two reads: dropping
     * `is_effective = 1` turns the post-void "empty" assertions red while these counts stay green.
     */
    @Test
    fun voidingAManualTransferRemovesItFromTheTagMerchantFilterAndAccumulationFaces() {
        P705Database.create("p705-t06-tag-merchant-").use { harness ->
            val transactionId = "tx-transfer-tag-merchant"
            val tagId = "tag-p705-void"
            val merchantId = "merchant-p705-void"
            harness.insertManualTransfer(transactionId)
            val catalogStore = harness.transferCatalogStore()
            harness.bootstrapTransferCatalog()
            val tagMerchantStore = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database)
            createCatalogItem(harness, CatalogItemKind.TAG, tagId)
            createCatalogItem(harness, CatalogItemKind.MERCHANT, merchantId)
            val expectedCatalogItemVersion = requireNotNull(tagMerchantStore.load(ledgerId)).catalogItemVersion
            seedAnnotation(harness, transactionId, tagId, merchantId)
            val adapter = SqlDelightTagMerchantFilterReadAdapter(harness.database, catalogStore, tagMerchantStore)

            fun pageTransactionIds(): List<String> =
                assertIs<TagMerchantFilterReadResult.Success>(
                    adapter.readPage(
                        ledgerId,
                        TagMerchantFilterCondition(tagIds = setOf(TagId(tagId)), merchantId = MerchantId(merchantId)),
                        expectedCatalogItemVersion,
                        10,
                        null,
                    ),
                ).page.entries.map { it.row.transactionId.value }

            fun accumulationTransactionIds(): List<String> =
                assertIs<MerchantAccumulationReadResult.Success>(
                    adapter.readMerchantRows(
                        ledgerId,
                        MerchantId(merchantId),
                        marchStart,
                        aprilStart,
                        expectedCatalogItemVersion,
                    ),
                ).rows.map { it.transactionId.value }

            // Non-degenerate baseline: both reads really see the annotated transfer first.
            assertEquals(listOf(transactionId), pageTransactionIds())
            assertEquals(listOf(transactionId), accumulationTransactionIds())
            assertEquals(listOf(1L, 1L, 1L), annotationCounts(harness))

            assertIs<VoidTransactionResult.Created>(voidTransfer(harness, transactionId))

            assertEquals(emptyList<String>(), pageTransactionIds())
            assertEquals(emptyList<String>(), accumulationTransactionIds())
            assertEquals(listOf(1L, 1L, 1L), annotationCounts(harness))
        }
    }

    /** Creates one tag/merchant through the REAL catalog command chain (claim/receipt/counter). */
    private fun createCatalogItem(
        harness: P705Database,
        kind: CatalogItemKind,
        itemId: String,
    ) {
        val executor =
            ExecuteTagMerchantCommand(
                commitPort = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId("catalog-$itemId") },
                idSource = CatalogItemIdSource { itemId },
                referenceProbe = CatalogItemReferenceProbe { _, _, _ -> false },
            )
        val name = if (kind == CatalogItemKind.TAG) "标签-$itemId" else "商家-$itemId"
        assertIs<TagMerchantCommandResult.Accepted>(executor.createItem(ledgerId, kind, name, 0L))
    }

    /** Appends annotation revision 1 through the same three named queries the product seam uses. */
    private fun seedAnnotation(
        harness: P705Database,
        transactionId: String,
        tagId: String,
        merchantId: String,
    ) {
        harness.database.ledgerQueries.insertTransactionAnnotationRevision(
            ledger_id = ledgerId.value,
            transaction_id = transactionId,
            annotation_revision = 1L,
            observed_transaction_version_id = "$transactionId-version-1",
            merchant_id = merchantId,
            request_id = "request-annot-$transactionId",
            created_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertTransactionAnnotationTag(ledgerId.value, transactionId, 1L, tagId)
        harness.database.ledgerQueries.insertTransactionAnnotationCurrent(ledgerId.value, transactionId, 1L)
    }

    private fun annotationCounts(harness: P705Database): List<Long> =
        listOf(
            "SELECT count(*) FROM transaction_annotation_revision",
            "SELECT count(*) FROM transaction_annotation_tag",
            "SELECT count(*) FROM transaction_annotation_current",
        ).map(harness::ledgerQueryCount)
}
