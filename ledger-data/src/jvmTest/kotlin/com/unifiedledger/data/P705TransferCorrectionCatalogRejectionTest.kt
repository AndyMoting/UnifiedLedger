package com.unifiedledger.data

import com.unifiedledger.application.CatalogAdmissionReader
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryKind
import kotlin.test.Test

class P705TransferCorrectionCatalogRejectionTest : P705TransferCorrectionTestSupport() {

    // ---- T-04 / T-05 admission negatives on the real commit port -----------------------------

    /**
     * T-04 (spec section 10 `:272`): a destination account in another currency against a CNY
     * target is the factory's same-currency admission (`AccountTransfer.kt:164-172`), a typed
     * catalog rejection with zero formal effect.
     */
    @Test
    fun foreignCurrencyDestinationCorrectionIsACatalogRejectionWithZeroWrites() {
        P705Database.create("p705-t-dest-currency-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-dest-currency", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-dest-currency")
            val before = journalSnapshot(harness, "tx-transfer-dest-currency")

            val result =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-dest-currency",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    destinationAccountId = usdBankId,
                    admissionReader = CatalogAdmissionReader { admissionCatalog(destination = realAssetBank(usdBankId, currency = usd)) },
                )

            assertRejectedWithoutFormalWrites(harness, "tx-transfer-dest-currency", before, result)
        }
    }

    /**
     * T-05 (spec section 10 `:273`): a fee category of the wrong kind is rejected by
     * `AccountTransfer.kt:273`. The posting account stays a hidden EXPENSE account in the
     * request ledger, so the kind of the category is the only broken condition.
     */
    @Test
    fun incomeFeeCategoryCorrectionIsACatalogRejectionWithZeroWrites() {
        P705Database.create("p705-t-fee-kind-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-kind", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-fee-kind")
            val before = journalSnapshot(harness, "tx-transfer-fee-kind")

            val result =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-fee-kind",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    admissionReader =
                        CatalogAdmissionReader {
                            admissionCatalog(
                                feeCategory =
                                    Category(
                                        P705Fixture.food,
                                        ledgerId,
                                        P705Fixture.foodParent,
                                        P705Fixture.expenseAccount,
                                        active = true,
                                        kind = CategoryKind.INCOME,
                                    ),
                            )
                        },
                )

            assertRejectedWithoutFormalWrites(harness, "tx-transfer-fee-kind", before, result)
        }
    }

    /**
     * T-05 (spec section 10 `:273`): a third-level fee category (fee category -> mid -> parent)
     * is rejected by the leaf-only rule at `AccountTransfer.kt:276`. Everything else about the
     * category - ledger, kind, active flag, posting account - is admissible.
     */
    @Test
    fun deepFeeCategoryCorrectionIsACatalogRejectionWithZeroWrites() {
        P705Database.create("p705-t-fee-leaf-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-leaf", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-fee-leaf")
            val before = journalSnapshot(harness, "tx-transfer-fee-leaf")

            val result =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-fee-leaf",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = threeLevelFeeCategoryId,
                    admissionReader =
                        CatalogAdmissionReader {
                            admissionCatalog(
                                feeCategory =
                                    Category(
                                        threeLevelFeeCategoryId,
                                        ledgerId,
                                        feeSubParentId,
                                        P705Fixture.expenseAccount,
                                        active = true,
                                    ),
                                feeParent = Category(feeSubParentId, ledgerId, P705Fixture.foodParent, null, active = true),
                            )
                        },
                )

            assertRejectedWithoutFormalWrites(harness, "tx-transfer-fee-leaf", before, result)
        }
    }

    /**
     * T-05 (spec section 10 `:273`): a deactivated fee category is the factory's fee-category
     * admission (`AccountTransfer.kt:274`), rejected with the same frozen code and zero formal
     * effect. The endpoint pair and the fee row target stay admissible.
     */
    @Test
    fun inactiveFeeCategoryCorrectionIsACatalogRejectionWithZeroWrites() {
        P705Database.create("p705-t-fee-inactive-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-inactive", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-fee-inactive")
            val before = journalSnapshot(harness, "tx-transfer-fee-inactive")

            val result =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-fee-inactive",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    admissionReader =
                        CatalogAdmissionReader {
                            admissionCatalog(
                                feeCategory = Category(P705Fixture.food, ledgerId, P705Fixture.foodParent, P705Fixture.expenseAccount, active = false),
                            )
                        },
                )

            assertRejectedWithoutFormalWrites(harness, "tx-transfer-fee-inactive", before, result)
        }
    }

    /**
     * T-05 (spec section 10 `:273`): a fee category whose ledger is another ledger is rejected
     * by `AccountTransfer.kt:272` while its parent and posting account stay fully admissible in
     * the request ledger - a single-factor cross-ledger negative.
     */
    @Test
    fun crossLedgerFeeCategoryCorrectionIsACatalogRejectionWithZeroWrites() {
        P705Database.create("p705-t-fee-ledger-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-ledger", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-fee-ledger")
            val before = journalSnapshot(harness, "tx-transfer-fee-ledger")

            val result =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-fee-ledger",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    admissionReader =
                        CatalogAdmissionReader {
                            admissionCatalog(
                                feeCategory = Category(P705Fixture.food, P705Fixture.otherLedgerId, P705Fixture.foodParent, P705Fixture.expenseAccount, active = true),
                            )
                        },
                )

            assertRejectedWithoutFormalWrites(harness, "tx-transfer-fee-ledger", before, result)
        }
    }

    /**
     * T-04 (spec section 10 `:272`; acceptance list section 3): a destination account in another
     * ledger is rejected by the reused creation admission (`AccountTransfer.kt:173-175`) with
     * zero formal effect. Without that ledger branch the factory would return a posting set, the
     * port would commit a new version and all three zero-write comparisons would red.
     */
    @Test
    fun crossLedgerDestinationCorrectionIsACatalogRejectionWithZeroWrites() {
        P705Database.create("p705-t-dest-ledger-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-dest-ledger", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-dest-ledger")
            val before = journalSnapshot(harness, "tx-transfer-dest-ledger")

            val result =
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-dest-ledger",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    destinationAccountId = crossLedgerBankId,
                    admissionReader = CatalogAdmissionReader { admissionCatalog(destination = realAssetBank(crossLedgerBankId, ledger = P705Fixture.otherLedgerId)) },
                )

            assertRejectedWithoutFormalWrites(harness, "tx-transfer-dest-ledger", before, result)
        }
    }
}
