package com.unifiedledger.data

import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P705TransferCorrectionGeometryTest : P705TransferCorrectionTestSupport() {

    /**
     * T-03b (spec section 10 `:271`; plan section 5 `05.B` at `:121`): a `fee == 0` transfer
     * corrected to `fee > 0` grows from two legs to three - the new posting set covers both old
     * principal legs exactly once, adds a `FEE` leg carrying the fee category, balances per
     * currency, and derives `sourceDebit == destinationCredit + fee` (spec section 3 `:116`,
     * section 8.3 `:242`/`:249`; `ACCOUNTING_RULES.md:273`/`:275`). Because the seed posts no
     * expense leg at all, the 150 in the expense surfaces below is exactly the new fee.
     *
     * The `posting` table has no source-posting binding column (`Ledger.sq:63-71`), so "covers
     * each old principal leg once" is asserted as an account/leg-order projection - each old
     * principal account exactly one row in the new set, in the frozen PRINCIPAL_OUT ->
     * PRINCIPAL_IN -> FEE order (`TransferCorrectionValidation.kt:80-81`) - and never as a
     * posting-identity binding.
     *
     * Red mechanism, face by face: dropping the FEE leg reds the leg order, the row count and
     * the expense assertions; a wrong leg order or a wrongly bound fee account reds the order
     * and the fee-account equality; a category that was not stored, or stored under another
     * request/category id, reds the request-row assertions; a source debit that is not
     * principal + fee reds the derived-source equality; a mis-stated fee amount reds both the
     * posting amount and the 150 expense total.
     */
    @Test
    fun zeroFeeSeedGainsACategorisedFeeLeg() {
        P705Database.create("p705-t-fee-add-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-add", destinationCreditMinor = 10_000L, feeMinor = 0L, note = null)
            val ids = TransferIds("p705-t-fee-add")
            // Bound to a local so the committed request row can be read back by its identity.
            val requestId = ids.requestId()

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(
                        harness,
                        ids,
                        requestId,
                        transactionId = "tx-transfer-fee-add",
                        destinationCreditMinor = 8_000L,
                        feeMinor = 150L,
                        feeCategoryId = P705Fixture.food,
                    ),
                )
            // T-03b: the two-leg seed gains a FEE leg on the fee category's posting account;
            // the derived source debit becomes 8_000 + 150.
            assertEquals(
                listOf(P705Fixture.bankA.value to -8_150L, P705Fixture.bankB.value to 8_000L, P705Fixture.expenseAccount.value to 150L),
                harness.currentPostings("tx-transfer-fee-add"),
            )
            // The leg count grew, so the new version must bind a fresh posting set: the frozen
            // write form only reuses the current set when the leg multiset is unchanged
            // (CorrectTransactionVersion.kt:165-168), and the commit port inserts the new set
            // and its postings in leg order (SqlDelightTransactionCorrectionCommitPort.kt:242-260).
            val newPostingSetId =
                harness.ledgerQueryText(
                    "SELECT posting_set_id FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'",
                )
            assertTrue(
                newPostingSetId != "tx-transfer-fee-add-posting-set-1",
                "a two-to-three-leg correction must bind a new posting set, got the seed set",
            )
            // Both old principal legs are covered exactly once, in the frozen leg order. The
            // seed set is bankA/bankB only, so a third row can only be the new FEE leg.
            assertEquals(
                listOf(P705Fixture.bankA.value, P705Fixture.bankB.value, P705Fixture.expenseAccount.value),
                harness.ledgerQueryTexts(
                    "SELECT account_id FROM posting WHERE posting_set_id = '$newPostingSetId' ORDER BY posting_index",
                ),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM posting WHERE posting_set_id = '$newPostingSetId' AND account_id = '${P705Fixture.bankA.value}'",
                ),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM posting WHERE posting_set_id = '$newPostingSetId' AND account_id = '${P705Fixture.bankB.value}'",
                ),
            )
            assertEquals(
                3L,
                harness.ledgerQueryCount("SELECT count(*) FROM posting WHERE posting_set_id = '$newPostingSetId'"),
            )
            // Per-currency balance of the new set (the fixture is CNY-only, so one group).
            assertEquals(
                listOf(0L),
                harness.ledgerQueryLongs(
                    "SELECT sum(amount_minor) FROM posting WHERE posting_set_id = '$newPostingSetId' GROUP BY currency_code",
                ),
            )
            // sourceDebit == destinationCredit + fee, read off the current postings and then
            // cross-checked against the stored request row: `amount_minor` stores
            // destinationCredit (CorrectTransactionVersion.kt:349-351) and `fee_minor` the fee.
            val postings = harness.currentPostings("tx-transfer-fee-add")
            val destinationCredit = postings[1].second
            val fee = postings[2].second
            assertEquals(8_000L, destinationCredit)
            assertEquals(150L, fee)
            assertEquals(-(destinationCredit + fee), postings[0].second)
            assertEquals(
                "8000",
                harness.ledgerQueryText(
                    "SELECT CAST(amount_minor AS TEXT) FROM transaction_correction_request WHERE request_id = '${requestId.value}'",
                ),
            )
            assertEquals(
                "150",
                harness.ledgerQueryText(
                    "SELECT CAST(fee_minor AS TEXT) FROM transaction_correction_request WHERE request_id = '${requestId.value}'",
                ),
            )
            // The committed request row must exist AND store the fee category. The row-count step
            // comes first because `ledgerQueryTextOrNull` answers null for a missing row exactly
            // as it does for a NULL value (P705Harness.kt:235-246), so without it "the category
            // was not carried" and "the row was never written" would be indistinguishable.
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM transaction_correction_request WHERE request_id = '${requestId.value}'",
                ),
            )
            assertEquals(
                P705Fixture.food.value,
                harness.ledgerQueryTextOrNull(
                    "SELECT category_id FROM transaction_correction_request WHERE request_id = '${requestId.value}'",
                ),
            )
            // The new FEE leg is posted on the fee category's own posting account - here both the
            // fixture constant and the injected catalog's mapping, so a leg bound to any other
            // account reds.
            val feeCategoryPostingAccount =
                requireNotNull(catalog.categories.single { it.id == P705Fixture.food }.postingAccountId)
            assertEquals(P705Fixture.expenseAccount.value, postings[2].first)
            assertEquals(feeCategoryPostingAccount.value, postings[2].first)
            // Ordinary expense and the spec's cash-outflow face equal the new fee. The seed was a
            // two-leg transfer with no bystander, so there is no other contribution to net out.
            val marchCurrency = marchActivity(harness).currencies.single()
            assertEquals(150L, marchCurrency.positiveExpenseMinorUnits)
            assertEquals(150L, marchCurrency.netExpenseMinorUnits)
            assertEquals(1, marchCurrency.transactionCount)
            assertEquals(1, marchCurrency.countByKind.getValue(TransactionKind.ACCOUNT_TRANSFER))
            val feeSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(150L, feeSummary.totalsByCurrency.single().expenseMinorUnits)
        }
    }

    /**
     * T-03 (spec section 10 `:270`; plan section 5 `05.B` at `:121`): a `fee > 0` transfer
     * corrected to `fee == 0` drops the FEE leg from the new posting set, and with it the fee
     * category reference (spec section 8.3 `:249`; `ACCOUNTING_RULES.md:273`). With the fee leg
     * gone the expense surfaces return to the non-transfer baseline - `ACCOUNTING_RULES.md:275`
     * is the invariant with the fee positive ("消费、普通费用与现金流出均等于手续费"), so this
     * vector pins the boundary where the fee stops existing. The spec's "cash outflow" wording
     * has no same-named field: `MonthlyCurrencyActivity` carries no `cashOutflow`, and
     * `MonthlyBuckets.kt:388` states `balanceMinorUnits` is not a cash flow, so the observable
     * landing points are `positiveExpenseMinorUnits`/`netExpenseMinorUnits` and
     * `CurrencyActivityTotal.expenseMinorUnits`.
     *
     * Both halves are asserted because a zero-to-zero comparison cannot go red. A non-zero
     * bystander expense keeps every surface below non-degenerate, and the pre-correction
     * control pins each of them to the bystander (5_000) plus the transfer's 200 fee. Red
     * mechanism: if the FEE leg survives in the new posting set, or the old three-leg set is
     * reused, the post-correction assertions read the control value 5_200 instead of 5_000.
     */
    @Test
    fun feeToZeroDropsTheFeeLegAndTheCategory() {
        P705Database.create("p705-t-fee-zero-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-zero", destinationCreditMinor = 10_000L, feeMinor = 200L)
            // A second, unrelated ordinary expense keeps the ledger's expense surface
            // non-degenerate, so the post-correction zero-contribution assertions below cannot
            // pass merely because nothing else contributed either (P705Harness.kt:622-643 seeds
            // expenseAccount +5_000, bankA -5_000 in the same March window).
            harness.insertOrdinaryExpense("tx-expense-fee-zero-bystander", amountMinor = 5_000L)
            val ids = TransferIds("p705-t-fee-zero")
            val requestId = ids.requestId()

            // Positive control BEFORE the correction: bystander 5_000 + the transfer's 200 fee
            // leg. The fee leg sits on the fee category's posting account,
            // P705Fixture.expenseAccount, which is AccountKind.EXPENSE, so it is ordinary
            // expense (SummarizeLedgerActivity.kt:61-67, MonthlyBuckets.kt:145-169).
            val beforeSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(5_200L, beforeSummary.totalsByCurrency.single().expenseMinorUnits)
            val beforeMarch = marchActivity(harness).currencies.single()
            assertEquals(5_200L, beforeMarch.positiveExpenseMinorUnits)
            assertEquals(5_200L, beforeMarch.netExpenseMinorUnits)
            assertEquals(2, beforeMarch.transactionCount)

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(harness, ids, requestId, transactionId = "tx-transfer-fee-zero", destinationCreditMinor = 10_000L, feeMinor = 0L, feeCategoryId = null),
                )
            // T-03: the FEE leg is dropped from the new posting set and the category reference
            // goes with it - two principal legs, category_id stored NULL (AR:273).
            assertEquals(
                listOf(P705Fixture.bankA.value to -10_000L, P705Fixture.bankB.value to 10_000L),
                harness.currentPostings("tx-transfer-fee-zero"),
            )
            // The transfer itself stays in the effective month surface: it is still counted, and
            // only its expense contribution is gone. Without this liveness pair "the transaction
            // disappeared" would also satisfy the expense assertions below.
            val afterMarch = marchActivity(harness).currencies.single()
            assertEquals(5_000L, afterMarch.positiveExpenseMinorUnits)
            assertEquals(5_000L, afterMarch.netExpenseMinorUnits)
            assertEquals(0L, afterMarch.refundMinorUnits)
            assertEquals(2, afterMarch.transactionCount)
            assertEquals(1, afterMarch.countByKind.getValue(TransactionKind.ACCOUNT_TRANSFER))
            val afterSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(5_000L, afterSummary.totalsByCurrency.single().expenseMinorUnits)
            assertNull(
                harness.ledgerQueryTextOrNull("SELECT category_id FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-fee-zero'"),
            )
            assertEquals(
                "0",
                harness.ledgerQueryText("SELECT CAST(fee_minor AS TEXT) FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-fee-zero'"),
            )
            // T-13 for the fee == 0 shape: replaying the same request id compares the stored
            // NULL category as stored and returns the original receipt, zero new entities.
            assertEquals(
                CorrectTransactionVersionResult.NoChange(created.receipt),
                correct(harness, ids, requestId, transactionId = "tx-transfer-fee-zero", destinationCreditMinor = 10_000L, feeMinor = 0L, feeCategoryId = null),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-fee-zero"))
        }
    }

    /**
     * The negative half of T-02's field set: a zero-fee transfer target that carries a fee
     * category is a typed rejection with zero formal effect. ACCOUNTING_RULES.md:273 forbids a
     * fee-less transfer from carrying a fee category, and spec section 9 rules that such a
     * target state reuses the frozen field code (`:258`) and that an unsupported path is always
     * a typed zero-write rejection, never a silent no-op (`:262`).
     */
    @Test
    fun aZeroFeeTargetCarryingACategoryIsAFieldRejection() {
        P705Database.create("p705-t-note2cat-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-note2-cat", destinationCreditMinor = 10_000L, feeMinor = 0L)
            val ids = TransferIds("p705-t-note2cat")
            val beforePostings = harness.currentPostings("tx-transfer-note2-cat")

            // The commit transaction's row-shape gate rejects this target state before the plan
            // callback runs (SqlDelightTransactionCorrectionCommitPort.kt:157-159 -> :400); the
            // domain planner refuses the same shape with the same frozen code independently
            // (TransferCorrectionValidation.kt:92-95), so neither layer would let it through.
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED),
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-note2-cat",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 0L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(1L, harness.versionCount("tx-transfer-note2-cat"))
            assertEquals(beforePostings, harness.currentPostings("tx-transfer-note2-cat"))
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            // The claim row is written first and then deleted by the rejection inside the same
            // transaction (SqlDelightTransactionCorrectionCommitPort.kt:446-452 via
            // Ledger.sq:10521-10523), so the committed journal holds zero rows - the identity
            // stays retryable and nothing else was written.
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    @Test
    fun ordinaryRowsRejectTheTransferColumnsAndAMissingCategory() {
        P705Database.create("p705-t-row-shape-").use { harness ->
            harness.insertOrdinaryExpense("tx-ordinary-shape", amountMinor = 10_000L)
            val ids = TransferIds("p705-t-row-shape")
            val useCase = ExecuteCorrectTransactionVersion(harness.correctionPort, ids.correctSource, P705Fixture.admissionReader)
            val request: (CategoryId?, AccountId?) -> ExplicitlyConfirmedTransactionCorrection =
                { categoryId, destinationAccountId ->
                    ExplicitlyConfirmedTransactionCorrection(
                        ledgerId = ledgerId,
                        requestId = ids.requestId(),
                        transactionId = TransactionId("tx-ordinary-shape"),
                        expectedCurrentVersionId = TransactionVersionId("tx-ordinary-shape-version-1"),
                        note = "corrected",
                        statisticsAt = P705Fixture.marchStatistics,
                        amount = Money.ofMinor(8_000L, P705Fixture.cny),
                        categoryId = categoryId,
                        fundingAccountId = P705Fixture.bankA,
                        destinationAccountId = destinationAccountId,
                        confirmation = ExplicitManualSave,
                    )
                }
            // Spec section 8.3's ordinary-row invariants, enforced by kind in the commit
            // transaction: an ordinary row may carry neither a destination account nor a fee,
            // and may never drop its category.
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED),
                useCase.execute(request(P705Fixture.food, P705Fixture.bankB)),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED),
                useCase.execute(request(null, null)),
            )
            assertEquals(1L, harness.versionCount("tx-ordinary-shape"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    @Test
    fun destinationCreditCorrectionReshapesAllThreeLegs() {
        P705Database.create("p705-t-amount-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-amount", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-amount")

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(
                        harness,
                        ids,
                        ids.requestId(),
                        transactionId = "tx-transfer-amount",
                        destinationCreditMinor = 8_000L,
                        feeMinor = 200L,
                        feeCategoryId = P705Fixture.food,
                    ),
                )
            // T-01: the new version binds a fresh posting set with the derived source debit
            // 8_000 + 200; the old set keeps its rows byte for byte.
            assertEquals(
                listOf(P705Fixture.bankA.value to -8_200L, P705Fixture.bankB.value to 8_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-amount"),
            )
            assertEquals(
                listOf(-10_200L, 10_000L, 200L),
                harness.ledgerQueryLongs("SELECT amount_minor FROM posting WHERE posting_set_id = 'tx-transfer-amount-posting-set-1' ORDER BY posting_index"),
            )
            // occurred_at/effective_at are copied verbatim; only statistics_at follows the request.
            assertEquals(
                harness.ledgerQueryText("SELECT occurred_at FROM transaction_version WHERE version_id = 'tx-transfer-amount-version-1'"),
                harness.ledgerQueryText("SELECT occurred_at FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals(
                harness.ledgerQueryText("SELECT effective_at FROM transaction_version WHERE version_id = 'tx-transfer-amount-version-1'"),
                harness.ledgerQueryText("SELECT effective_at FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            // The committed request row stores the transfer columns of the section-8.3 mapping.
            assertEquals(
                P705Fixture.food.value,
                harness.ledgerQueryTextOrNull("SELECT category_id FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-amount'"),
            )
            assertEquals(
                P705Fixture.bankB.value,
                harness.ledgerQueryTextOrNull("SELECT destination_account_id FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-amount'"),
            )
            assertEquals(
                "200",
                harness.ledgerQueryText("SELECT CAST(fee_minor AS TEXT) FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-amount'"),
            )
        }
    }

    @Test
    fun endpointSwapMovesBothPrincipalLegs() {
        P705Database.create("p705-t-endpoints-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-endpoints", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-endpoints")

            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-endpoints",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    sourceAccountId = P705Fixture.bankB,
                    destinationAccountId = P705Fixture.bankA,
                ),
            )
            // T-04: both endpoints moved; the fee leg stays on the fee category's account.
            assertEquals(
                listOf(P705Fixture.bankB.value to -10_200L, P705Fixture.bankA.value to 10_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-endpoints"),
            )
        }
    }
}
