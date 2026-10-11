package com.unifiedledger.data

import com.unifiedledger.application.AnnotationRequestId
import com.unifiedledger.application.AnnotationRequestIdSource
import com.unifiedledger.application.CatalogItemIdSource
import com.unifiedledger.application.CatalogItemReferenceProbe
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteTagMerchantCommand
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantRequestId
import com.unifiedledger.application.TagMerchantRequestIdSource
import com.unifiedledger.application.TransactionAnnotationResult
import com.unifiedledger.application.TransactionAnnotationSnapshot
import com.unifiedledger.application.UpdateTransactionAnnotation
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-08 08.D A03 evidence (spec sections 3.3/3.4; plan :302; the acceptance registration belongs
 * to the main agent — nothing here records a vector PASS). The legs ride REAL command chains:
 *
 *  - the P7-05 correction port (`ExecuteCorrectTransactionVersion`) keeps the root annotation
 *    across the financial versions: the current pointer and the association survive untouched,
 *    the surviving revision's `observed_transaction_version_id` stays the version the annotation
 *    operation SAW (the honest semantics, never backfilled to the new version), the correction
 *    request/receipt shape is the P7-05 protocol verbatim (four identity fields, zero annotation
 *    fields), and the amounts/postings/reconciliation surfaces carry no extra change;
 *  - void hides the annotation from the effective/filter reads without deleting a single
 *    annotation row, and restore brings it back (spec 3.3; §5.5 :227 restore 后注释重新可见).
 *
 * All data synthetic and anonymous with fixed instants.
 */
class P708CorrectionAnnotationInteractionTest {
    private val transactionId = TransactionId("tx-expense-100")
    private val firstVersionId = TransactionVersionId("tx-expense-100-version-1")

    @Test
    fun aFinancialCorrectionKeepsTheRootAnnotationAndTheHonestObservedVersion() {
        P705Database.create("p708-a03-correction-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val tagId = AnnotationHarness.createTag(harness, "tag-1")
            AnnotationHarness.createCatalogItem(harness, com.unifiedledger.domain.CatalogItemKind.MERCHANT, "merchant-1")
            AnnotationHarness.annotate(harness, transactionId, tagId, MerchantId("merchant-1"), expectedVersion = firstVersionId)

            // Before: revision 1 observed the version the annotation operation saw.
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_revision"))
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM transaction_annotation_revision WHERE observed_transaction_version_id = '${firstVersionId.value}' AND merchant_id = 'merchant-1'",
                ),
            )
            val before = AnnotationHarness.load(harness, transactionId)
            assertEquals(1L, before!!.currentAnnotationRevision)
            assertEquals(listOf(tagId), before.tagIds)

            val ids = P705Ids("p708-a03-correction")
            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    AnnotationHarness.correct(harness, ids),
                )
            // (iii) The correction request/receipt shape is the P7-05 protocol verbatim: exactly
            // the four identity fields, zero annotation fields (the type IS the protocol).
            assertEquals(transactionId, created.receipt.transactionId)
            assertEquals(firstVersionId, created.receipt.expectedCurrentVersionId)
            val newVersionId = created.receipt.versionId
            assertEquals(ids.lastVersionId, newVersionId.value)

            // (i) The root annotation survives the correction: same revision, same association.
            val after = AnnotationHarness.load(harness, transactionId)
            assertEquals(1L, after!!.currentAnnotationRevision)
            assertEquals(listOf(tagId), after.tagIds)
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_revision"))
            // (ii) The surviving revision keeps its ORIGINAL observed version (honest semantics,
            // never backfilled to the corrected version).
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM transaction_annotation_revision WHERE annotation_revision = 1 AND observed_transaction_version_id = '${firstVersionId.value}'",
                ),
            )
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_revision WHERE observed_transaction_version_id = '$newVersionId'"))

            // (iv) The financial surface carries exactly the correction's own change: two
            // versions, four postings (2 original + 2 corrected, 100 -> 80), and zero
            // reconciliation rows.
            assertEquals(2L, harness.versionCount("tx-expense-100"))
            assertEquals(4L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            assertEquals(
                listOf(8_000L, -8_000L),
                harness.ledgerQueryLongs(
                    "SELECT p.amount_minor FROM posting p JOIN transaction_version v ON v.posting_set_id = p.posting_set_id " +
                        "WHERE v.version_id = '${newVersionId.value}' ORDER BY p.posting_index",
                ),
            )
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM posting_reconciliation"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM evidence_link"))
        }
    }

    @Test
    fun voidHidesTheAnnotationReadsAndRestoreBringsThemBackWithoutDeletingARow() {
        P705Database.create("p708-a03-void-restore-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val tagId = AnnotationHarness.createTag(harness, "tag-1")
            AnnotationHarness.annotate(harness, transactionId, tagId, merchantId = null, expectedVersion = firstVersionId)
            val filter = AnnotationHarness.filterAdapter(harness)
            val generation = AnnotationHarness.catalogGeneration(harness)
            val condition = TagMerchantFilterCondition(tagIds = setOf(tagId))

            fun pageIds(): List<String> =
                when (val result = filter.readPage(P705Fixture.ledgerId, condition, generation, 10, null)) {
                    is TagMerchantFilterReadResult.Success -> result.page.entries.map { it.row.transactionId.value }
                    else -> error("filter read must succeed in this vector: $result")
                }

            // Effective and visible: the annotation read and the filter read both see it.
            assertEquals(listOf("tx-expense-100"), pageIds())
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(P705Fixture.ledgerId).size)

            val ids = P705Ids("p708-a03-void-restore")
            assertIs<VoidTransactionResult.Created>(AnnotationHarness.voidTransaction(harness, ids))
            // Void hides the transaction from the effective and filter reads (spec 3.3)...
            assertTrue(pageIds().isEmpty())
            assertEquals(0, harness.readAdapter.loadLedgerEntryRows(P705Fixture.ledgerId).size)
            // ...but deletes no annotation row (void 不删注释).
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_revision"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_tag"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_current"))

            assertIs<VoidTransactionResult.Created>(AnnotationHarness.restoreTransaction(harness, ids))
            // Restore re-surfaces the same annotation on the effective read face (§5.5 :227).
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(P705Fixture.ledgerId).size)
            assertEquals(listOf("tx-expense-100"), pageIds())
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_revision WHERE observed_transaction_version_id = '${firstVersionId.value}'"))
        }
    }
}

/** Shared anonymous harness pieces for the 08.D interaction tests (file-backed P705 fixture). */
private object AnnotationHarness {
    val clock: LedgerClock = LedgerClock { Instant.parse("2026-03-05T02:00:00Z") }

    /** Creates one tag/merchant through the REAL catalog command chain (claim/receipt/counter). */
    fun createCatalogItem(
        harness: P705Database,
        kind: com.unifiedledger.domain.CatalogItemKind,
        itemId: String,
    ) {
        val executor =
            ExecuteTagMerchantCommand(
                commitPort = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId("catalog-$itemId") },
                idSource = CatalogItemIdSource { itemId },
                referenceProbe = CatalogItemReferenceProbe { _, _, _ -> false },
            )
        val name = if (kind == com.unifiedledger.domain.CatalogItemKind.TAG) "标签-$itemId" else "商家-$itemId"
        assertIs<TagMerchantCommandResult.Accepted>(
            executor.createItem(P705Fixture.ledgerId, kind, name, 0L),
        )
    }

    fun createTag(
        harness: P705Database,
        tagId: String,
    ): TagId {
        createCatalogItem(harness, com.unifiedledger.domain.CatalogItemKind.TAG, tagId)
        return TagId(tagId)
    }

    /** Appends annotation revision 1 through the REAL annotation command chain. */
    fun annotate(
        harness: P705Database,
        transactionId: TransactionId,
        tagId: TagId,
        merchantId: MerchantId?,
        expectedVersion: TransactionVersionId,
    ) {
        val result =
            UpdateTransactionAnnotation(
                commitPort = SqlDelightTransactionAnnotationStore.forPlatformConfiguredDatabase(harness.database),
                requestIdSource = AnnotationRequestIdSource { AnnotationRequestId("annot-$transactionId") },
                clock = clock,
            ).update(P705Fixture.ledgerId, transactionId, listOf(tagId), merchantId, 0L, expectedVersion)
        assertIs<TransactionAnnotationResult.Accepted>(result)
    }

    fun load(
        harness: P705Database,
        transactionId: TransactionId,
    ): TransactionAnnotationSnapshot? = SqlDelightTransactionAnnotationStore.forPlatformConfiguredDatabase(harness.database).load(P705Fixture.ledgerId, transactionId)

    fun correct(
        harness: P705Database,
        ids: P705Ids,
    ): CorrectTransactionVersionResult =
        ExecuteCorrectTransactionVersion(harness.correctionPort, ids.correctSource, P705Fixture.admissionReader).execute(
            ExplicitlyConfirmedTransactionCorrection(
                ledgerId = P705Fixture.ledgerId,
                requestId = ids.requestId(),
                transactionId = TransactionId("tx-expense-100"),
                expectedCurrentVersionId = TransactionVersionId("tx-expense-100-version-1"),
                note = "corrected",
                statisticsAt = P705Fixture.marchStatistics,
                amount = Money.ofMinor(8_000L, P705Fixture.cny),
                categoryId = P705Fixture.food,
                fundingAccountId = P705Fixture.bankA,
                confirmation = ExplicitManualSave,
            ),
        )

    fun voidTransaction(
        harness: P705Database,
        ids: P705Ids,
    ): VoidTransactionResult =
        ExecuteVoidTransaction(harness.voidPort, ids.voidSource, fixedClock(P705Fixture.voidedAt)).execute(
            VoidTransactionRequest(
                ledgerId = P705Fixture.ledgerId,
                requestId = ids.requestId(),
                transactionId = TransactionId("tx-expense-100"),
                reason = VoidReason(VoidReasonCode.MIS_ENTERED, "wrong amount"),
                confirmation = ExplicitManualSave,
            ),
        )

    fun restoreTransaction(
        harness: P705Database,
        ids: P705Ids,
    ): VoidTransactionResult =
        ExecuteRestoreTransaction(harness.voidPort, ids.voidSource, fixedClock(Instant.parse("2026-05-01T01:00:00Z")), P705Fixture.admissionReader).execute(
            VoidTransactionRequest(
                ledgerId = P705Fixture.ledgerId,
                requestId = ids.requestId(),
                transactionId = TransactionId("tx-expense-100"),
                reason = VoidReason(VoidReasonCode.VOIDED_IN_ERROR, "restore"),
                confirmation = ExplicitManualSave,
            ),
        )

    /**
     * The bounded filter read over the same database (the 08.C read the UI uses). The seed names
     * exactly the accounts the P705 fixture's postings reference — bootstrap fails closed on any
     * unresolved posting reference, so the default seed cannot be used here.
     */
    fun filterAdapter(harness: P705Database): SqlDelightTagMerchantFilterReadAdapter {
        val accountCatalog = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)
        val seed =
            com.unifiedledger.data.CatalogSeed(
                accounts =
                    listOf(
                        com.unifiedledger.data.CatalogSeedAccount(P705Fixture.bankA, "银行A", com.unifiedledger.domain.AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                        com.unifiedledger.data.CatalogSeedAccount(P705Fixture.bankB, "银行B", com.unifiedledger.domain.AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                        com.unifiedledger.data.CatalogSeedAccount(P705Fixture.expenseAccount, "餐饮支出", com.unifiedledger.domain.AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = false),
                        com.unifiedledger.data.CatalogSeedAccount(P705Fixture.incomeAccount, "收入", com.unifiedledger.domain.AccountKind.INCOME, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = false),
                    ),
                categories = emptyList(),
            )
        accountCatalog.bootstrap(P705Fixture.ledgerId, seed)
        return SqlDelightTagMerchantFilterReadAdapter(
            database = harness.database,
            accountCatalogReader = accountCatalog,
            tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
        )
    }

    /** The tag/merchant catalog generation the filter read compares inside its read transaction. */
    fun catalogGeneration(harness: P705Database): Long =
        requireNotNull(SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database).load(P705Fixture.ledgerId)).catalogItemVersion
}
