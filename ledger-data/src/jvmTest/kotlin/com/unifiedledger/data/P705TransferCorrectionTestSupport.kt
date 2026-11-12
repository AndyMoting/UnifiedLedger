package com.unifiedledger.data

import com.unifiedledger.application.CatalogAdmissionReader
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionIdSource
import com.unifiedledger.application.CorrectTransactionVersionIds
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryMonthlyActivity
import com.unifiedledger.application.RequestId
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
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-05 transfer-slice correction commit evidence (transfer spec sections 3/8.3; D-228 J1, and
 * the slice-3 DP-10 narrowing + D-113 composition of D-228 J3): real claim-first port commits
 * of manual-transfer corrections across the frozen field set - note, destination credit, both
 * endpoints and the fee (both leg-count directions) - plus the CAS/replay family and the
 * voided-target rejection. Every vector seeds a real manual transfer chain (transaction,
 * version 1, posting set, postings, current-version pointer) in the frozen creation leg order.
 *
 * The DP-10/J3 matrix vectors seed the reconciliation and evidence rows the product path never
 * writes (manual transfers carry none, J3 evidence facts 2/3): a PENDING placeholder row
 * (evidence_link_id NULL), and an active evidence link whose invalidation the narrowed gate
 * must compose inside the correction transaction. Transfer restore end-to-end lives in
 * `P705TransferRestorePathTest`; values are anonymous synthetic.
 */
internal abstract class P705TransferCorrectionTestSupport {
    protected val ledgerId = P705Fixture.ledgerId
    protected val catalog: LedgerCatalog = P705Fixture.catalog()

    protected fun state(harness: P705Database): LedgerCurrentState =
        assertIs<LedgerCurrentStateResult.Success>(
            QueryLedgerCurrentState(harness.readAdapter, ledgerId, catalog).query(),
        ).state

    protected fun marchActivity(harness: P705Database): MonthlyActivity =
        assertIs<MonthlyActivityResult.Success>(
            QueryMonthlyActivity(
                harness.readAdapter,
                ledgerId,
                catalog,
                fixedClock(Instant.parse("2026-03-20T00:00:00Z")),
            ).query(YearMonth(2026, 3)),
        ).activity

    /** Self-sufficient id source: mints the transfer slice's third (FEE-leg) posting id too. */
    protected class TransferIds(
        private val prefix: String,
    ) {
        private var counter = 0

        private fun next(): String = "$prefix-${++counter}"

        fun requestId(): RequestId = RequestId(next())

        val correctSource: CorrectTransactionVersionIdSource =
            CorrectTransactionVersionIdSource {
                CorrectTransactionVersionIds(
                    confirmationId = ConfirmationId(next()),
                    versionId = TransactionVersionId(next()),
                    postingSetId = PostingSetId(next()),
                    categoryPostingId = PostingId(next()),
                    fundingPostingId = PostingId(next()),
                    feePostingId = PostingId(next()),
                )
            }
    }

    /**
     * Seeds one manual ACCOUNT_TRANSFER chain in the creation factory's frozen leg order
     * (PRINCIPAL_OUT, PRINCIPAL_IN, FEE on the fee category's posting account when the fee is
     * positive); no reconciliation/evidence row is written (J3 evidence facts 2/3).
     */
    protected fun insertManualTransfer(
        harness: P705Database,
        transactionId: String,
        sourceAccount: AccountId = P705Fixture.bankA,
        destinationAccount: AccountId = P705Fixture.bankB,
        destinationCreditMinor: Long,
        feeMinor: Long,
        note: String? = "rent",
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
            note,
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

    protected fun correct(
        harness: P705Database,
        ids: TransferIds,
        requestId: RequestId,
        transactionId: String,
        expectedVersionId: TransactionVersionId = TransactionVersionId("$transactionId-version-1"),
        note: String? = "corrected",
        destinationCreditMinor: Long,
        feeMinor: Long,
        feeCategoryId: CategoryId?,
        sourceAccountId: AccountId = P705Fixture.bankA,
        destinationAccountId: AccountId = P705Fixture.bankB,
        statisticsAt: Instant = P705Fixture.marchStatistics,
        admissionReader: CatalogAdmissionReader = P705Fixture.admissionReader,
    ): CorrectTransactionVersionResult =
        ExecuteCorrectTransactionVersion(harness.correctionPort, ids.correctSource, admissionReader)
            .execute(
                ExplicitlyConfirmedTransactionCorrection(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    transactionId = TransactionId(transactionId),
                    expectedCurrentVersionId = expectedVersionId,
                    note = note,
                    statisticsAt = statisticsAt,
                    amount = Money.ofMinor(destinationCreditMinor, P705Fixture.cny),
                    categoryId = feeCategoryId,
                    fundingAccountId = sourceAccountId,
                    destinationAccountId = destinationAccountId,
                    feeMinor = feeMinor,
                    confirmation = ExplicitManualSave,
                ),
            )

    /**
     * The complete column tuple of every row in [table], read through the same driver the
     * claim-first port uses, with a multi-column mapper so NULLs keep their place instead of
     * swallowing neighbouring values. Rows are ordered by all columns, which makes the
     * comparison deterministic without assuming a particular primary key.
     */
    protected fun ownerRowsOf(
        harness: P705Database,
        table: String,
    ): List<List<String?>> {
        val columns = harness.ledgerQueryTexts("SELECT name FROM pragma_table_info('$table') ORDER BY cid")
        val projection = columns.joinToString(", ")
        val order = columns.joinToString(", ")
        return harness.driverQuery(
            "SELECT $projection FROM $table WHERE ledger_id = ? ORDER BY $order",
            ledgerId.value,
        ) { cursor ->
            buildList {
                while (cursor.next().value) {
                    add(columns.indices.map { index -> cursor.getString(index) })
                }
            }
        }
    }

    /**
     * Seeds the `catalog_account` rows the narrowed DP-10 gate reads inside the port
     * (`selectCatalogAccounts` -> the real-account set of `affectedFundingLegs`), mirroring
     * [P705Fixture]'s injected catalog: the two real asset endpoints and the fee category's
     * hidden non-real expense posting account. The harness deliberately injects the admission
     * catalog in memory (P705Harness.kt KDoc), so without these rows the port's own catalog read
     * sees an empty ledger and no leg can ever classify as a real funding leg.
     */
    protected fun seedCatalogRows(harness: P705Database) {
        fun account(
            accountId: AccountId,
            kind: String,
            realAccount: Long,
        ) {
            harness.database.ledgerQueries.insertCatalogAccount(
                ledger_id = ledgerId.value,
                account_id = accountId.value,
                name = "seed-${accountId.value}",
                kind = kind,
                currency_code = P705Fixture.cny.code,
                currency_precision = P705Fixture.cny.precision.toLong(),
                owned_by_user = 1L,
                real_account = realAccount,
                system_role = null,
                hidden = if (realAccount == 1L) 0L else 1L,
                active = 1L,
            )
        }
        account(P705Fixture.bankA, "ASSET", 1L)
        account(P705Fixture.bankB, "ASSET", 1L)
        account(P705Fixture.expenseAccount, "EXPENSE", 0L)
    }

    /**
     * Seeds a PENDING placeholder reconciliation row (evidence_link_id NULL, the v23-seed shape
     * of spec section 1.6) on one posting, through the same named queries the P408 writers use.
     */
    protected fun seedPendingPlaceholder(
        harness: P705Database,
        postingId: String,
        reconciliationId: String,
    ) {
        val seedRequestId = "seed-$reconciliationId-request"
        harness.database.ledgerQueries.claimP408ReconciliationRequest(ledgerId.value, seedRequestId, "migration_seed", "seed-$reconciliationId")
        harness.database.ledgerQueries.updateP408ReconciliationRequest("ACCEPTED", null, ledgerId.value, seedRequestId)
        harness.database.ledgerQueries.insertP408PostingReconciliation(ledgerId.value, reconciliationId, postingId)
        harness.database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
            sequence = 1L,
            status = "PENDING",
            evidence_link_id = null,
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
    }

    /**
     * Seeds one active evidence link on a posting - the row A fixture the product path cannot
     * produce (J3 evidence fact 3): the import evidence chain the link FKs need, an
     * `evidence_link` with `active/confirmed` history, and the posting's reconciliation row
     * advanced to CHECKED exactly the way a confirmLink commit leaves it.
     */
    protected fun seedActiveLink(
        harness: P705Database,
        transactionId: String,
        postingId: String,
        linkId: String,
        reconciliationId: String,
    ) {
        val seedRequestId = "seed-$linkId-request"
        harness.database.ledgerQueries.claimP408ReconciliationRequest(ledgerId.value, seedRequestId, "confirm_link", "seed-$linkId")
        harness.database.ledgerQueries.updateP408ReconciliationRequest("ACCEPTED", null, ledgerId.value, seedRequestId)
        harness.database.ledgerQueries.claimImportRequest(ledgerId.value, "seed-$linkId-import", "confirm_candidate")
        harness.database.ledgerQueries.insertImportSourceRecord(
            ledgerId.value,
            "seed-$linkId-source",
            "seed-$linkId-import",
            "input-ref-$linkId",
            0L,
            "ordinary_flow_source",
            "content-hash-$linkId",
            1L,
            "valid_incomplete",
            null,
            null,
            null,
            null,
            null,
            null,
            "UNRESOLVED",
            "rule-$linkId",
            1L,
            P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertImportEvidence(
            ledgerId.value,
            "evidence-$linkId",
            "seed-$linkId-source",
            "source_observation",
            P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408PostingReconciliation(ledgerId.value, reconciliationId, postingId)
        harness.database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
            sequence = 1L,
            status = "PENDING",
            evidence_link_id = null,
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408EvidenceLink(
            ledger_id = ledgerId.value,
            link_id = linkId,
            evidence_id = "evidence-$linkId",
            posting_id = postingId,
            transaction_id = transactionId,
            responsibility = "real_account_posting",
            basis_version = 2L,
            match_basis = "exact",
            candidate_id = "candidate-$linkId",
            request_id = seedRequestId,
            created_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408EvidenceLinkHistory(
            ledger_id = ledgerId.value,
            link_id = linkId,
            sequence = 1L,
            state = "active",
            reason = "confirmed",
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
            sequence = 2L,
            status = "CHECKED",
            evidence_link_id = linkId,
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.updateP408PostingReconciliation(
            status = "CHECKED",
            latest_sequence = 2L,
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
        )
    }

    /** This file's foreign-currency fixture; [P705Fixture] only carries CNY. */
    protected val usd = CurrencyUnit("USD", 2)
    protected val crossLedgerBankId = AccountId("asset-cross-ledger-bank")
    protected val usdBankId = AccountId("asset-bank-usd")
    protected val threeLevelFeeCategoryId = CategoryId("category-food-three-level")
    protected val feeSubParentId = CategoryId("category-food-sub-parent")

    protected fun realAssetBank(
        id: AccountId,
        ledger: LedgerId = ledgerId,
        currency: CurrencyUnit = P705Fixture.cny,
        ownedByUser: Boolean = true,
    ): Account = Account(id, ledger, AccountKind.ASSET, currency, ownedByUser = ownedByUser, realAccount = true)

    /**
     * The default source, [destination], the hidden EXPENSE posting account and one valid fee
     * category - the same fixture [P705Fixture.catalog] injects, parameterised so that each
     * negative below differs from the positive baseline in exactly one attribute and the
     * rejection can only be attributed to that attribute.
     */
    protected fun admissionCatalog(
        destination: Account = realAssetBank(P705Fixture.bankB),
        feeCategory: Category = Category(P705Fixture.food, ledgerId, P705Fixture.foodParent, P705Fixture.expenseAccount, active = true),
        feeParent: Category = Category(P705Fixture.foodParent, ledgerId, null, null, active = true),
    ): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(P705Fixture.bankA, ledgerId, AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true),
                            destination,
                            Account(P705Fixture.expenseAccount, ledgerId, AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false),
                        ),
                    categories = listOf(feeParent, feeCategory),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("admission fixture catalog must be valid")
        }

    /**
     * The three tables a rejected correction must leave unchanged, plus the CAS pointer the
     * version chain must keep. A rejection that stopped writing before the plan would still
     * change nothing, so the baseline assertions in [assertRejectedWithoutFormalWrites] prove
     * the comparison is over non-empty facts.
     */
    protected data class TransferJournalSnapshot(
        val versionCount: Long,
        val currentVersionId: String,
        val postings: List<Pair<String, Long>>,
        val postingCount: Long,
        val requestCount: Long,
    )

    protected fun journalSnapshot(
        harness: P705Database,
        transactionId: String,
    ): TransferJournalSnapshot =
        TransferJournalSnapshot(
            versionCount = harness.versionCount(transactionId),
            currentVersionId = harness.currentVersionId(transactionId),
            postings = harness.currentPostings(transactionId),
            postingCount = harness.ledgerQueryCount("SELECT count(*) FROM posting"),
            requestCount = harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"),
        )

    protected fun assertRejectedWithoutFormalWrites(
        harness: P705Database,
        transactionId: String,
        before: TransferJournalSnapshot,
        result: CorrectTransactionVersionResult,
    ) {
        assertEquals(
            CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE),
            result,
        )
        // Non-degenerate baseline: one seed version, its three legs and an empty claim journal.
        // A zero-vs-zero comparison over an empty ledger would prove nothing.
        assertEquals(1L, before.versionCount)
        assertEquals("$transactionId-version-1", before.currentVersionId)
        assertEquals(3L, before.postingCount)
        assertEquals(0L, before.requestCount)
        val after = journalSnapshot(harness, transactionId)
        assertEquals(before.versionCount, after.versionCount)
        assertEquals(before.currentVersionId, after.currentVersionId)
        assertEquals(before.postings, after.postings)
        assertEquals(before.postingCount, after.postingCount)
        assertEquals(before.requestCount, after.requestCount)
    }
}
