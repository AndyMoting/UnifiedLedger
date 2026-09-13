package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-03.A transaction detail projection vectors (spec sections 4.2.2, 3.2.1, C03):
 * effective kind, both times, note, legs with account/category current names, absent
 * category presentation (P703SPEC-09), creation-entry lineage precedence including the
 * mirror vector (P703SPEC-11), read-only multi-leg reconciliation eligibility and the
 * frozen rollup priority chain, plus fail-closed results. All data synthetic/anonymous.
 */
class QueryTransactionDetailTest {
    private val ledgerId = LedgerId("ledger-detail-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetId = AccountId("account-asset")
    private val receivableId = AccountId("account-receivable")
    private val expenseId = AccountId("account-expense-breakfast")
    private val incomeId = AccountId("account-income-salary")
    private val foodParentId = CategoryId("category-food")
    private val breakfastId = CategoryId("category-breakfast")

    @Test
    fun unknownTransactionYieldsNotFound() {
        val result = query(rows = listOf(lendRow()), transactionId = TransactionId("tx-unknown"))
        assertIs<TransactionDetailResult.NotFound>(result)
    }

    @Test
    fun detailProjectsKindBothTimesNoteLegsAndCategoryNames() {
        val expenseRow =
            LedgerEntryRow(
                transactionId = TransactionId("tx-expense"),
                currentVersionId = TransactionVersionId("version-expense-2"),
                kind = TransactionKind.EXPENSE,
                occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
                statisticsAt = Instant.parse("2026-03-05T02:00:00Z"),
                note = " breakfast note ",
                postings =
                    listOf(
                        Posting(PostingId("posting-expense"), expenseId, Money.ofMinor(3_000L, cny)),
                        Posting(PostingId("posting-payment"), assetId, Money.ofMinor(-3_000L, cny)),
                    ),
            )
        val detail = assertIs<TransactionDetailResult.Success>(query(listOf(expenseRow), TransactionId("tx-expense"))).detail

        assertEquals(TransactionKind.EXPENSE, detail.kind)
        assertEquals(Instant.parse("2026-03-01T02:00:00Z"), detail.occurredAt)
        assertEquals(Instant.parse("2026-03-05T02:00:00Z"), detail.statisticsAt)
        assertEquals(" breakfast note ", detail.note)
        assertEquals(TransactionVersionId("version-expense-2"), detail.currentVersionId)
        assertEquals(2, detail.legs.size)
        val expenseLeg = detail.legs[0]
        assertEquals(expenseId, expenseLeg.accountId)
        assertEquals("支出-早餐账户", expenseLeg.accountName)
        assertEquals(3_000L, expenseLeg.amount.minorUnits)
        assertEquals(cny, expenseLeg.amount.currency)
        assertEquals("早餐（改名后）", expenseLeg.categoryName)
        // Real-account leg without a category mapping: absent category, never a failure.
        assertNull(detail.legs[1].categoryName)
        assertEquals(CreationEntry.UNMARKED, detail.creationEntry)
    }

    @Test
    fun realAccountLegsWithoutCategoryMappingStayAbsentNotFailed() {
        // P703SPEC-09: a lending transaction's principal legs carry no catalog_category
        // mapping; the detail succeeds with absent categories (无分类).
        val detail = assertIs<TransactionDetailResult.Success>(query(listOf(lendRow()), TransactionId("tx-lend"))).detail
        assertEquals(TransactionKind.LEND, detail.kind)
        assertTrue(detail.legs.all { it.categoryName == null })
        assertTrue(detail.legs.all { it.accountName.isNotEmpty() })
    }

    @Test
    fun creationEntryPrefersImportThenManualThenUnmarked() {
        val imported =
            QueryTransactionDetail(
                EntryDetailPort(rows = listOf(expenseRow()), importConfirmation = importConfirmation()),
                ledgerId,
                catalog(),
            ).query(TransactionId("tx-expense"))
        assertEquals(CreationEntry.IMPORT_CREATED, assertIs<TransactionDetailResult.Success>(imported).detail.creationEntry)

        val manual =
            QueryTransactionDetail(
                EntryDetailPort(rows = listOf(expenseRow()), manualReceipt = ManualCreationReceiptRow(ManualCreationChain.EXPENSE, "confirmation-manual")),
                ledgerId,
                catalog(),
            ).query(TransactionId("tx-expense"))
        assertEquals(CreationEntry.MANUAL_CREATED, assertIs<TransactionDetailResult.Success>(manual).detail.creationEntry)

        val unmarked =
            QueryTransactionDetail(EntryDetailPort(rows = listOf(expenseRow())), ledgerId, catalog()).query(TransactionId("tx-expense"))
        assertEquals(CreationEntry.UNMARKED, assertIs<TransactionDetailResult.Success>(unmarked).detail.creationEntry)

        // Frozen precedence: when both lineages exist the import confirmation wins.
        val both =
            QueryTransactionDetail(
                EntryDetailPort(rows = listOf(expenseRow()), importConfirmation = importConfirmation(), manualReceipt = ManualCreationReceiptRow(ManualCreationChain.EXPENSE, "confirmation-manual")),
                ledgerId,
                catalog(),
            ).query(TransactionId("tx-expense"))
        assertEquals(CreationEntry.IMPORT_CREATED, assertIs<TransactionDetailResult.Success>(both).detail.creationEntry)
    }

    @Test
    fun mirrorEvidenceDoesNotChangeTheCreationEntry() {
        // P703SPEC-11 mirror vector: a manual entry that later receives mirror evidence
        // keeps 手工创建 — the existence of an evidence link cannot prove an import creation.
        val evidenceLegs = listOf(legRow("posting-expense", hasActiveEvidenceLink = true))
        val detail =
            assertIs<TransactionDetailResult.Success>(
                QueryTransactionDetail(
                    EntryDetailPort(
                        rows = listOf(expenseRow()),
                        manualReceipt = ManualCreationReceiptRow(ManualCreationChain.EXPENSE, "confirmation-manual"),
                        legs = evidenceLegs,
                    ),
                    ledgerId,
                    catalog(),
                ).query(TransactionId("tx-expense")),
            ).detail
        assertEquals(CreationEntry.MANUAL_CREATED, detail.creationEntry)
        // The mirrored real-account leg gained eligibility via the active evidence chain.
        val reconciliationLegs = detail.reconciliation.legs
        assertTrue(reconciliationLegs.single { it.leg.postingId == PostingId("posting-expense") }.eligible)
    }

    @Test
    fun reconciliationLegsProjectEligibilityAndFrozenRollup() {
        // rg03 transfer: principal leg eligible with a PARTIAL reconciliation row, fee leg
        // explicitly ineligible (reconciliation_eligible = 0 wins over the evidence link),
        // destination principal leg eligible with the PENDING default.
        val legs =
            listOf(
                legRow(
                    "posting-transfer-source",
                    status = "PARTIAL",
                    hasReconciliationRow = true,
                    rg03Eligible = true,
                ),
                legRow(
                    "posting-transfer-fee",
                    status = "PENDING",
                    hasActiveEvidenceLink = true,
                    rg03Eligible = false,
                ),
                legRow(
                    "posting-transfer-destination",
                    status = "PENDING",
                    rg03Eligible = true,
                ),
            )
        val transferRow =
            LedgerEntryRow(
                transactionId = TransactionId("tx-transfer"),
                currentVersionId = TransactionVersionId("version-transfer-1"),
                kind = TransactionKind.ACCOUNT_TRANSFER,
                occurredAt = Instant.parse("2026-03-02T02:00:00Z"),
                statisticsAt = Instant.parse("2026-03-02T02:00:00Z"),
                note = null,
                postings =
                    listOf(
                        Posting(PostingId("posting-transfer-source"), assetId, Money.ofMinor(-6_000L, cny)),
                        Posting(PostingId("posting-transfer-fee"), expenseId, Money.ofMinor(100L, cny)),
                        Posting(PostingId("posting-transfer-destination"), receivableId, Money.ofMinor(5_900L, cny)),
                    ),
            )
        val detail =
            assertIs<TransactionDetailResult.Success>(
                QueryTransactionDetail(EntryDetailPort(rows = listOf(transferRow), legs = legs), ledgerId, catalog())
                    .query(TransactionId("tx-transfer")),
            ).detail

        val sourceLeg = detail.reconciliation.legs[0]
        assertEquals(true, sourceLeg.eligible)
        assertEquals(P408ReconciliationStatus.PARTIAL, sourceLeg.status)
        val feeLeg = detail.reconciliation.legs[1]
        assertEquals(false, feeLeg.eligible)
        assertNull(feeLeg.status)
        val destinationLeg = detail.reconciliation.legs[2]
        assertEquals(true, destinationLeg.eligible)
        assertEquals(P408ReconciliationStatus.PENDING, destinationLeg.status)
        // Rollup only over eligible legs: PARTIAL outranks PENDING (R-Q07-4).
        assertEquals(P408ReconciliationStatus.PARTIAL, detail.reconciliation.rollup)
    }

    @Test
    fun legsWithoutAnyEligibilityPresentNoRollup() {
        // Pure manual ledger: no rg03 rows, no evidence chain, no reconciliation rows —
        // every leg shows 无对账资格 and no transaction rollup exists (spec section 3.2.1).
        val detail =
            assertIs<TransactionDetailResult.Success>(
                QueryTransactionDetail(EntryDetailPort(rows = listOf(lendRow()), legs = listOf(legRow("posting-lend-out"), legRow("posting-lend-in"))), ledgerId, catalog())
                    .query(TransactionId("tx-lend")),
            ).detail
        assertTrue(detail.reconciliation.legs.all { !it.eligible })
        assertNull(detail.reconciliation.rollup)
    }

    @Test
    fun rollupFollowsTheFrozenPriorityChain() {
        assertNull(rollupReconciliationStatus(emptyList()))
        assertEquals(P408ReconciliationStatus.MISSING, rollupReconciliationStatus(listOf(P408ReconciliationStatus.PENDING, P408ReconciliationStatus.MISSING)))
        assertEquals(P408ReconciliationStatus.DIFFERENCE, rollupReconciliationStatus(listOf(P408ReconciliationStatus.DIFFERENCE, P408ReconciliationStatus.CHECKED)))
        assertEquals(P408ReconciliationStatus.PARTIAL, rollupReconciliationStatus(listOf(P408ReconciliationStatus.PARTIAL, P408ReconciliationStatus.CHECKED)))
        assertEquals(P408ReconciliationStatus.CHECKED, rollupReconciliationStatus(listOf(P408ReconciliationStatus.CHECKED, P408ReconciliationStatus.CHECKED)))
        assertEquals(P408ReconciliationStatus.PENDING, rollupReconciliationStatus(listOf(P408ReconciliationStatus.PENDING, P408ReconciliationStatus.CHECKED)))
        assertEquals(P408ReconciliationStatus.PENDING, rollupReconciliationStatus(listOf(P408ReconciliationStatus.PENDING)))
    }

    @Test
    fun eligibilityPureFunctionAppliesSection321Precedence() {
        // (a) explicit rg03 semantic: eligible principal leg.
        assertEquals(true, isReconciliationEligible(legRow("p1", rg03Eligible = true)))
        // (a)-exclusion: the fee leg stays ineligible even with evidence and a status row.
        assertEquals(
            false,
            isReconciliationEligible(legRow("p2", hasActiveEvidenceLink = true, hasReconciliationRow = true, rg03Eligible = false)),
        )
        // (b): existing evidence chain or reconciliation status row without rg03 semantics.
        assertEquals(true, isReconciliationEligible(legRow("p3", hasActiveEvidenceLink = true)))
        assertEquals(true, isReconciliationEligible(legRow("p4", hasReconciliationRow = true)))
        // Plain manual leg: no artifacts, no eligibility.
        assertEquals(false, isReconciliationEligible(legRow("p5")))
    }

    @Test
    fun readPortFailuresYieldUnavailableAndCatalogInconsistencyYieldsInvalidState() {
        assertIs<TransactionDetailResult.Unavailable>(
            QueryTransactionDetail(ThrowingDetailPort(), ledgerId, catalog()).query(TransactionId("tx-expense")),
        )
        val inconsistentRow =
            LedgerEntryRow(
                transactionId = TransactionId("tx-bad"),
                currentVersionId = TransactionVersionId("version-bad-1"),
                kind = TransactionKind.EXPENSE,
                occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
                statisticsAt = Instant.parse("2026-03-01T02:00:00Z"),
                note = null,
                postings =
                    listOf(
                        Posting(PostingId("posting-bad"), AccountId("account-unknown"), Money.ofMinor(1L, cny)),
                    ),
            )
        assertIs<TransactionDetailResult.InvalidState>(
            QueryTransactionDetail(EntryDetailPort(rows = listOf(inconsistentRow)), ledgerId, catalog()).query(TransactionId("tx-bad")),
        )
    }

    @Test
    fun unimplementedLineageAndReconciliationReadsFailClosedInsteadOfAnsweringDefinitively() {
        // G6/R-Q06-4: the three sibling P7-03 reads carry no neutral default either. An
        // unimplemented port must not render a definitive 来源未标注 (as if no lineage existed) or
        // an all-ineligible 无对账资格 projection.
        val port = EntryRowsWithoutLineagePort(listOf(expenseRow()))
        assertFailsWith<UnsupportedOperationException> {
            port.findImportCreationConfirmation(ledgerId, TransactionId("tx-expense"))
        }
        assertFailsWith<UnsupportedOperationException> {
            port.findManualCreationReceipt(ledgerId, TransactionId("tx-expense"))
        }
        assertFailsWith<UnsupportedOperationException> {
            port.loadTransactionReconciliationLegs(ledgerId, TransactionId("tx-expense"))
        }
        assertIs<TransactionDetailResult.Unavailable>(
            QueryTransactionDetail(port, ledgerId, catalog()).query(TransactionId("tx-expense")),
        )
    }

    // --- fixtures -----------------------------------------------------------------------

    private fun lendRow() =
        LedgerEntryRow(
            transactionId = TransactionId("tx-lend"),
            currentVersionId = TransactionVersionId("version-lend-1"),
            kind = TransactionKind.LEND,
            occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
            statisticsAt = Instant.parse("2026-03-01T02:00:00Z"),
            note = "lend note",
            postings =
                listOf(
                    Posting(PostingId("posting-lend-out"), assetId, Money.ofMinor(-10_000L, cny)),
                    Posting(PostingId("posting-lend-in"), receivableId, Money.ofMinor(10_000L, cny)),
                ),
        )

    private fun expenseRow() =
        LedgerEntryRow(
            transactionId = TransactionId("tx-expense"),
            currentVersionId = TransactionVersionId("version-expense-1"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
            statisticsAt = Instant.parse("2026-03-01T02:00:00Z"),
            note = null,
            postings =
                listOf(
                    Posting(PostingId("posting-expense"), expenseId, Money.ofMinor(3_000L, cny)),
                    Posting(PostingId("posting-payment"), assetId, Money.ofMinor(-3_000L, cny)),
                ),
        )

    private fun importConfirmation() =
        ImportCreationConfirmationRow(
            confirmationId = "confirmation-import",
            requestId = "request-import",
            candidateId = "candidate-import",
            transactionId = TransactionId("tx-expense"),
            operationClass = "creation",
            confirmedAt = "2026-03-01T02:00:00Z",
        )

    private fun legRow(
        postingId: String,
        status: String = "PENDING",
        hasReconciliationRow: Boolean = false,
        hasActiveEvidenceLink: Boolean = false,
        rg03Eligible: Boolean? = null,
    ) = TransactionReconciliationLegRow(
        postingId = PostingId(postingId),
        postingIndex = 0,
        accountId = assetId,
        amountMinor = 1_000L,
        currency = cny,
        statusStorageValue = status,
        hasReconciliationRow = hasReconciliationRow,
        hasActiveEvidenceLink = hasActiveEvidenceLink,
        rg03ReconciliationEligible = rg03Eligible,
    )

    private fun query(
        rows: List<LedgerEntryRow>,
        transactionId: TransactionId,
    ): TransactionDetailResult = QueryTransactionDetail(EntryDetailPort(rows = rows), ledgerId, catalog()).query(transactionId)

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "资产-现金"),
                            Account(receivableId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "资产-应收"),
                            Account(expenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "支出-早餐账户"),
                            Account(incomeId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false, name = "收入-工资账户"),
                        ),
                    categories =
                        listOf(
                            Category(foodParentId, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮"),
                            Category(breakfastId, ledgerId, parentId = foodParentId, postingAccountId = expenseId, active = true, name = "早餐（改名后）"),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }
}

/** Read-port fake serving the P7-03 detail surfaces with fixed synthetic rows. */
private class EntryDetailPort(
    private val rows: List<LedgerEntryRow>,
    private val importConfirmation: ImportCreationConfirmationRow? = null,
    private val manualReceipt: ManualCreationReceiptRow? = null,
    private val legs: List<TransactionReconciliationLegRow> = emptyList(),
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = null

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = null

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null

    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> = rows

    override fun findImportCreationConfirmation(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ImportCreationConfirmationRow? = importConfirmation

    override fun findManualCreationReceipt(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): ManualCreationReceiptRow? = manualReceipt

    override fun loadTransactionReconciliationLegs(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): List<TransactionReconciliationLegRow> = legs
}

/**
 * G6: a read port that implements only the pre-P7-03 surface plus the entry-row read, so the three
 * sibling P7-03 read defaults (creation lineage + reconciliation legs) are exercised as inherited
 * behaviour rather than as explicit overrides.
 */
private class EntryRowsWithoutLineagePort(
    private val rows: List<LedgerEntryRow>,
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = null

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = null

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null

    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> = rows
}

private class ThrowingDetailPort : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = throw IllegalStateException("database unavailable")

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = throw IllegalStateException("database unavailable")

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null

    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> = throw IllegalStateException("database unavailable")
}
