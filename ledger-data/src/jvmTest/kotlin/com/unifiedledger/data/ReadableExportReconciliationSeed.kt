package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/**
 * P8-05 批 a real-DB reconciliation seed (the DefBFlowPageReadTest raw-SQL discipline — the
 * real schema, synthetic values, explicit rows so every leg shape is deliberate). All times are
 * UTC instants whose Shanghai wall clock is unambiguous; ids are synthetic tokens without
 * UUID-shaped segments where the assertion surfaces mention them.
 *
 * Coverage (D-331 §5 V-1/V-6, D-E5 每组至少一条):
 * - ① EXPENSE (+35.80 category leg / −35.80 asset) and INCOME (−2000/+2000) [rg-01:1660, rg-10:663]
 * - ② ACCOUNT_TRANSFER without fee (−500/+500), with fee (−500/+499/+1 fee category) [rg-03],
 *   LEND (−100/+100), COLLECT (+100/−100), CREDIT_REPAYMENT (−800/+800)
 * - ③ BALANCE_ADJUSTMENT (+30/−30) and BALANCE_ADJUSTMENT_REVERSAL (−20/+20) [rg-09:328-376]
 * - ④ OPENING_BALANCE (+1000/−1000) [rg-01:175-188], STORED_VALUE_RECHARGE single (+1200/−1200)
 *   [rg-10 single-leg recharge], STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT (+50/−50)
 * - ⑤ STORED_VALUE_SPEND (+300/−300 category/asset) [rg-10:1742-1758], STORED_VALUE_EXPIRY_LOSS
 *   (+25/−25); PREPAID_PURCHASE is NOT in this ledger (its golden rg-11 real shape —
 *   payment_asset −N + prepaid_asset +N, NO category leg — falls through D-E5 ⑤ and would turn
 *   the Succeeded walk into a data-condition stop; it is pinned by
 *   [seedPrepaidPurchaseGoldenShape] + the dedicated fall-through vector test)
 * - ⑥ PREPAID_RECOGNITION (+33.33 category leg / −33.33 prepaid) [rg-11:901-919]
 * - ⑦ REFUND_RECEIPT (−12/+12)
 * - the unnamed-account placeholder leg (seed-acc-ghost has no catalog row → 账户1 = 未命名账户,
 *   分类 empty since the ghost leg has no mapping);
 * - V-6: one VOIDED expense (the effective predicate excludes it);
 * - 多版本修正: one expense with version 2 as current (different note/amount; v1 ignored);
 * - 同秒并列: two transactions sharing one statistics instant (ids decide the page order);
 * - 跨币种转账: CNY outflow −500 / JPY inflow +10_000 (JPY precision 0);
 * - an over-bound note on the LAST page (seeded separately with the OLDEST statistics instant;
 *   R6 中途检出).
 *
 * ③④ 兜底腿形（发生率记录）跑在 ledger-application jvmTest 的 dedicated unresolved vector
 * （两条分类腿形态）上：本对账账本若混入 PRESENTATION_UNRESOLVED 行，结局是
 * Failed(PRESENTATION_UNRESOLVED)（D-E10 数据条件中止），行级对账断言必须在 Succeeded 结局上
 * 做，故兜底向量单独成测。
 */
internal object ReadableExportReconciliationSeed {
    private const val SEED_NOTE_SHORT = "备注甲"
    private const val OVERSIZED_NOTE_LENGTH = 32_768

    fun seed(
        driver: SqlDriver,
        ledger: LedgerId,
    ) {
        val l = ledger.value
        seedCatalog(driver, l)
        // ④ 期初 (the balance base; statistics 2026-01-01 00:00 Shanghai).
        insertTransaction(
            driver,
            l,
            id = "seed-opening",
            kind = "OPENING_BALANCE",
            canonical = null,
            occurred = "2025-12-31T16:00:00Z",
            statistics = "2025-12-31T16:00:00Z",
            note = SEED_NOTE_SHORT,
            legs = listOf("seed-acc-bank" to (100_000L to "CNY:2"), "seed-acc-equity" to (-100_000L to "CNY:2")),
        )
        // ① 支出 (+35.80; v1 only).
        insertTransaction(
            driver,
            l,
            id = "seed-expense",
            kind = "EXPENSE",
            canonical = null,
            occurred = "2026-01-15T02:14:40Z",
            statistics = "2026-01-15T02:14:40Z",
            note = SEED_NOTE_SHORT,
            legs = listOf("seed-acc-expense-food" to (3_580L to "CNY:2"), "seed-acc-bank" to (-3_580L to "CNY:2")),
        )
        // ① 收入 (原符号 −2000).
        insertTransaction(
            driver,
            l,
            id = "seed-income",
            kind = "INCOME",
            canonical = null,
            occurred = "2026-01-20T02:30:00Z",
            statistics = "2026-01-20T02:30:00Z",
            note = null,
            legs = listOf("seed-acc-income-salary" to (-200_000L to "CNY:2"), "seed-acc-bank" to (200_000L to "CNY:2")),
        )
        // ② 转账无手续费.
        insertTransaction(
            driver,
            l,
            id = "seed-transfer",
            kind = "ACCOUNT_TRANSFER",
            canonical = null,
            occurred = "2026-01-21T03:00:00Z",
            statistics = "2026-01-21T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-bank" to (-50_000L to "CNY:2"), "seed-acc-wallet" to (50_000L to "CNY:2")),
        )
        // ② 转账含手续费（分类腿 = fee）.
        insertTransaction(
            driver,
            l,
            id = "seed-transfer-fee",
            kind = "ACCOUNT_TRANSFER",
            canonical = null,
            occurred = "2026-01-21T04:00:00Z",
            statistics = "2026-01-21T04:00:00Z",
            note = null,
            legs =
                listOf(
                    "seed-acc-bank" to (-50_000L to "CNY:2"),
                    "seed-acc-wallet" to (49_900L to "CNY:2"),
                    "seed-acc-fee" to (100L to "CNY:2"),
                ),
        )
        // ② 借出 / 收回 / 信用还款.
        insertTransaction(
            driver,
            l,
            id = "seed-lend",
            kind = "ACCOUNT_TRANSFER",
            canonical = "LEND",
            occurred = "2026-01-22T03:00:00Z",
            statistics = "2026-01-22T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-bank" to (-10_000L to "CNY:2"), "seed-acc-receivable" to (10_000L to "CNY:2")),
        )
        insertTransaction(
            driver,
            l,
            id = "seed-collect",
            kind = "ACCOUNT_TRANSFER",
            canonical = "COLLECT",
            occurred = "2026-01-23T03:00:00Z",
            statistics = "2026-01-23T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-receivable" to (-10_000L to "CNY:2"), "seed-acc-bank" to (10_000L to "CNY:2")),
        )
        insertTransaction(
            driver,
            l,
            id = "seed-repay",
            kind = "ACCOUNT_TRANSFER",
            canonical = "CREDIT_REPAYMENT",
            occurred = "2026-01-24T03:00:00Z",
            statistics = "2026-01-24T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-bank" to (-80_000L to "CNY:2"), "seed-acc-credit" to (80_000L to "CNY:2")),
        )
        // ③ 余额调整与冲正（golden rg-09 符号）.
        insertTransaction(
            driver,
            l,
            id = "seed-adjust",
            kind = "EXPENSE",
            canonical = "BALANCE_ADJUSTMENT",
            occurred = "2026-01-25T03:00:00Z",
            statistics = "2026-01-25T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-bank" to (3_000L to "CNY:2"), "seed-acc-equity" to (-3_000L to "CNY:2")),
        )
        insertTransaction(
            driver,
            l,
            id = "seed-adjust-reversal",
            kind = "EXPENSE",
            canonical = "BALANCE_ADJUSTMENT_REVERSAL",
            occurred = "2026-01-26T03:00:00Z",
            statistics = "2026-01-26T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-bank" to (-2_000L to "CNY:2"), "seed-acc-equity" to (2_000L to "CNY:2")),
        )
        // ④ 储值充值（单腿形态）与激活前调整.
        insertTransaction(
            driver,
            l,
            id = "seed-recharge",
            kind = "EXPENSE",
            canonical = "STORED_VALUE_RECHARGE",
            occurred = "2026-01-27T03:00:00Z",
            statistics = "2026-01-27T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-wallet" to (120_000L to "CNY:2"), "seed-acc-bank" to (-120_000L to "CNY:2")),
        )
        insertTransaction(
            driver,
            l,
            id = "seed-pre-activation",
            kind = "EXPENSE",
            canonical = "STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT",
            occurred = "2026-01-28T03:00:00Z",
            statistics = "2026-01-28T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-wallet" to (5_000L to "CNY:2"), "seed-acc-equity" to (-5_000L to "CNY:2")),
        )
        // ⑤ 储值消费 / 过期损失（golden rg-10:1742-1758 符号）.
        insertTransaction(
            driver,
            l,
            id = "seed-spend",
            kind = "EXPENSE",
            canonical = "STORED_VALUE_SPEND",
            occurred = "2026-01-29T03:00:00Z",
            statistics = "2026-01-29T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-expense-food" to (30_000L to "CNY:2"), "seed-acc-wallet" to (-30_000L to "CNY:2")),
        )
        insertTransaction(
            driver,
            l,
            id = "seed-expiry",
            kind = "EXPENSE",
            canonical = "STORED_VALUE_EXPIRY_LOSS",
            occurred = "2026-01-30T03:00:00Z",
            statistics = "2026-01-30T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-expense-service" to (2_500L to "CNY:2"), "seed-acc-wallet" to (-2_500L to "CNY:2")),
        )
        // ⑥ 预付确认（golden rg-11:901-919）.
        insertTransaction(
            driver,
            l,
            id = "seed-recognition",
            kind = "EXPENSE",
            canonical = "PREPAID_RECOGNITION",
            occurred = "2026-01-31T07:33:20Z",
            statistics = "2026-01-31T07:33:20Z",
            note = null,
            legs = listOf("seed-acc-expense-service" to (3_333L to "CNY:2"), "seed-acc-prepaid" to (-3_333L to "CNY:2")),
        )
        // 无名账户占位（②转账形）: seed-acc-ghost 不在 catalog → 账户1 落「未命名账户」且该行
        // 可解析（ghost 作流出腿）；① 组下 ghost 行必然未解（两条腿都无分类映射）会整体中止.
        insertTransaction(
            driver,
            l,
            id = "seed-unnamed",
            kind = "ACCOUNT_TRANSFER",
            canonical = null,
            occurred = "2026-02-02T04:00:00Z",
            statistics = "2026-02-02T04:00:00Z",
            note = null,
            legs = listOf("seed-acc-ghost" to (-500L to "CNY:2"), "seed-acc-bank" to (500L to "CNY:2")),
        )
        // 空白名账户: catalog 行存在但名字为空 → 账户1 落「未命名账户」. 同为②转账形，保持可解析.
        insertTransaction(
            driver,
            l,
            id = "seed-blank-name",
            kind = "ACCOUNT_TRANSFER",
            canonical = null,
            occurred = "2026-02-02T05:00:00Z",
            statistics = "2026-02-02T05:00:00Z",
            note = null,
            legs = listOf("seed-acc-blank-name" to (-700L to "CNY:2"), "seed-acc-bank" to (700L to "CNY:2")),
        )
        // ⑦ 退款到账（负支出）.
        insertTransaction(
            driver,
            l,
            id = "seed-refund",
            kind = "EXPENSE",
            canonical = "REFUND_RECEIPT",
            occurred = "2026-02-02T03:00:00Z",
            statistics = "2026-02-02T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-expense-food" to (-1_200L to "CNY:2"), "seed-acc-bank" to (1_200L to "CNY:2")),
        )
        // 跨币种转账（JPY precision 0; 金额取账户1 转出腿）.
        insertTransaction(
            driver,
            l,
            id = "seed-fx-transfer",
            kind = "ACCOUNT_TRANSFER",
            canonical = null,
            occurred = "2026-02-05T03:00:00Z",
            statistics = "2026-02-05T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-bank" to (-50_000L to "CNY:2"), "seed-acc-jpy" to (10_000L to "JPY:0")),
        )
        // 多版本修正: v1 被取代，v2 是当前版本.
        insertTransaction(
            driver,
            l,
            id = "seed-corrected",
            kind = "EXPENSE",
            canonical = null,
            occurred = "2026-02-06T03:00:00Z",
            statistics = "2026-02-06T03:00:00Z",
            note = "修正后",
            legs = listOf("seed-acc-expense-service" to (9_900L to "CNY:2"), "seed-acc-bank" to (-9_900L to "CNY:2")),
            priorVersionNote = "修正前",
            priorLegs = listOf("seed-acc-expense-service" to (19_900L to "CNY:2"), "seed-acc-bank" to (-19_900L to "CNY:2")),
        )
        // 同秒并列: 同一 statistics instant，id 顺序决定页序.
        insertTransaction(
            driver,
            l,
            id = "seed-tie-a",
            kind = "EXPENSE",
            canonical = null,
            occurred = "2026-02-07T03:00:00Z",
            statistics = "2026-02-07T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-expense-food" to (1_00L to "CNY:2"), "seed-acc-bank" to (-1_00L to "CNY:2")),
        )
        insertTransaction(
            driver,
            l,
            id = "seed-tie-b",
            kind = "EXPENSE",
            canonical = null,
            occurred = "2026-02-07T03:00:00Z",
            statistics = "2026-02-07T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-expense-food" to (2_00L to "CNY:2"), "seed-acc-bank" to (-2_00L to "CNY:2")),
        )
        // V-6: 作废支出（不在有效集）.
        insertTransaction(
            driver,
            l,
            id = "seed-voided",
            kind = "EXPENSE",
            canonical = null,
            occurred = "2026-02-08T03:00:00Z",
            statistics = "2026-02-08T03:00:00Z",
            note = "作废行",
            legs = listOf("seed-acc-expense-food" to (9_999L to "CNY:2"), "seed-acc-bank" to (-9_999L to "CNY:2")),
            voided = true,
        )
        // D-E6 来源列: 手工回执（支出/转账/收入/借出四链）+ 导入确认回链.
        insertManualReceipt(driver, l, chain = "expense", confirmationId = "seed-manual-expense-c", transactionId = "seed-expense")
        insertManualReceipt(driver, l, chain = "transfer", confirmationId = "seed-manual-transfer-c", transactionId = "seed-transfer")
        insertManualReceipt(driver, l, chain = "income", confirmationId = "seed-manual-income-c", transactionId = "seed-income")
        insertManualReceipt(driver, l, chain = "lending", confirmationId = "seed-manual-lend-c", transactionId = "seed-lend")
        insertImportLineage(driver, l, confirmationId = "seed-import-c", transactionId = "seed-recognition")
    }

    /**
     * Seeds the over-bound note transaction (R6 中途检出 leg on the real database): its
     * statistics instant is the OLDEST of the ledger, so with the frozen `(statistics_at DESC,
     * transaction_id DESC)` page order it lands on the LAST page — the walk streams the resolved
     * first page(s) first and only then hits the bound (the page size in the test is 3).
     */
    fun seedOversizedNote(
        driver: SqlDriver,
        ledger: LedgerId,
    ) {
        insertTransaction(
            driver,
            ledger.value,
            id = "seed-note-huge",
            kind = "EXPENSE",
            canonical = null,
            occurred = "2025-12-30T03:00:00Z",
            statistics = "2025-12-30T03:00:00Z",
            note = "长".repeat(OVERSIZED_NOTE_LENGTH),
            legs = listOf("seed-acc-expense-food" to (1_00L to "CNY:2"), "seed-acc-bank" to (-1_00L to "CNY:2")),
        )
    }

    /**
     * Seeds ONE PREPAID_PURCHASE in its golden rg-11 real shape (states/main-01): the
     * `payment_asset` leg −N (cash) and the `prepaid_asset` leg +N — NO category leg. The D-E5
     * ⑤ group's 「金额 = 分类腿」contract does NOT cover this two-asset-leg shape, so the row
     * falls through to the row-level 「（呈现未定）」placeholder and the export stops with
     * PRESENTATION_UNRESOLVED (never committed). This is the registered spec-vs-golden deviation:
     * the spec lists PREPAID_PURCHASE in group ⑤, but the golden leg shape is category-less, and
     * the implementation honestly falls through instead of inventing a category-anchored amount
     * (D-032 不得猜测). The dedicated vector test pins this so no later change "fixes" it by
     * wrongly re-anchoring PREPAID_PURCHASE onto a category leg.
     */
    fun seedPrepaidPurchaseGoldenShape(
        driver: SqlDriver,
        ledger: LedgerId,
    ) {
        insertTransaction(
            driver,
            ledger.value,
            id = "seed-prepaid-purchase",
            kind = "EXPENSE",
            canonical = "PREPAID_PURCHASE",
            occurred = "2026-02-01T03:00:00Z",
            statistics = "2026-02-01T03:00:00Z",
            note = null,
            legs = listOf("seed-acc-prepaid" to (10_000L to "CNY:2"), "seed-acc-bank" to (-10_000L to "CNY:2")),
        )
    }

    // ---------------------------------------------------------------- catalog + rows

    /**
     * The product catalog the presentation consumes. The hidden expense/income posting accounts
     * follow the validateProductCatalog shape (systemRole NULL, not owned, not real, kind
     * matched); the unknown-account leg (`seed-acc-ghost`) is deliberately absent from the
     * catalog for the placeholder vector.
     */
    private fun seedCatalog(
        driver: SqlDriver,
        l: String,
    ) {
        exec(driver, "INSERT INTO catalog_version (ledger_id, version) VALUES ('$l', 1)")

        fun account(
            id: String,
            kind: String,
            name: String,
            owned: Int = 1,
            real: Int = 1,
        ) = exec(
            driver,
            "INSERT INTO catalog_account (ledger_id, account_id, name, kind, currency_code, currency_precision, owned_by_user, real_account, system_role, hidden, active) " +
                "VALUES ('$l', '$id', '$name', '$kind', 'CNY', 2, $owned, $real, NULL, 0, 1)",
        )
        account("seed-acc-bank", "ASSET", "招商银行")
        account("seed-acc-wallet", "ASSET", "零钱")
        account("seed-acc-prepaid", "ASSET", "预付资产")
        account("seed-acc-jpy", "ASSET", "日元户")
        account("seed-acc-credit", "LIABILITY", "信用卡")
        account("seed-acc-receivable", "ASSET", "应收款")
        account("seed-acc-equity", "EQUITY", "期初权益")
        account("seed-acc-income-bonus", "INCOME", "赠送收入", owned = 0, real = 0)
        account("seed-acc-income-salary", "INCOME", "工资收入", owned = 0, real = 0)
        account("seed-acc-expense-food", "EXPENSE", "餐饮支出", owned = 0, real = 0)
        account("seed-acc-expense-service", "EXPENSE", "服务支出", owned = 0, real = 0)
        account("seed-acc-fee", "EXPENSE", "手续费", owned = 0, real = 0)
        // The blank-name vector: an account row whose CURRENT name is empty → 「未命名账户」.
        account("seed-acc-blank-name", "ASSET", "")

        fun category(
            id: String,
            kind: String,
            parent: String?,
            postingAccount: String?,
        ) = exec(
            driver,
            "INSERT INTO catalog_category (ledger_id, category_id, kind, parent_id, posting_account_id, active) " +
                "VALUES ('$l', '$id', '$kind', ${parent?.let { "'$it'" } ?: "NULL"}, ${postingAccount?.let { "'$it'" } ?: "NULL"}, 1)",
        )
        // catalog_category carries no name; the CURRENT name rides catalog_name_history. The
        // prepaid asset account deliberately carries NO category mapping: PREPAID_PURCHASE's
        // golden shape (payment_asset −N / prepaid_asset +N) must fall through D-E5 ⑤ honestly.
        // The fee account DOES carry its mapping: without it the fee leg joins the non-category
        // set and the three-leg fee transfer exceeds the group-2 two-non-category shape.
        category("seed-cat-food-parent", "EXPENSE", null, null)
        category("seed-cat-food-leaf", "EXPENSE", "seed-cat-food-parent", "seed-acc-expense-food")
        category("seed-cat-service-parent", "EXPENSE", null, null)
        category("seed-cat-service-leaf", "EXPENSE", "seed-cat-service-parent", "seed-acc-expense-service")
        category("seed-cat-income-parent", "INCOME", null, null)
        category("seed-cat-salary-leaf", "INCOME", "seed-cat-income-parent", "seed-acc-income-salary")
        category("seed-cat-fee", "EXPENSE", "seed-cat-service-parent", "seed-acc-fee")

        fun categoryName(
            ownerId: String,
            name: String,
        ) = exec(
            driver,
            "INSERT INTO catalog_name_history (ledger_id, owner_kind, owner_id, version_number, name, status) " +
                "VALUES ('$l', 'category', '$ownerId', 1, '$name', 'CURRENT')",
        )
        categoryName("seed-cat-food-parent", "餐饮")
        categoryName("seed-cat-food-leaf", "早餐")
        categoryName("seed-cat-service-parent", "服务")
        categoryName("seed-cat-service-leaf", "服务费")
        categoryName("seed-cat-income-parent", "收入")
        categoryName("seed-cat-salary-leaf", "工资")
        categoryName("seed-cat-fee", "手续费")
    }

    private fun insertTransaction(
        driver: SqlDriver,
        l: String,
        id: String,
        kind: String,
        canonical: String?,
        occurred: String,
        statistics: String,
        note: String?,
        legs: List<Pair<String, Pair<Long, String>>>,
        priorVersionNote: String? = null,
        priorLegs: List<Pair<String, Pair<Long, String>>>? = null,
        voided: Boolean = false,
    ) {
        exec(driver, "INSERT INTO ledger_transaction (transaction_id, ledger_id, kind, canonical_kind) VALUES ('$id', '$l', '$kind', ${canonical?.let { "'$it'" } ?: "NULL"})")

        fun insertVersion(
            versionId: String,
            versionNumber: Int,
            postingSetId: String,
            versionNote: String?,
            legs: List<Pair<String, Pair<Long, String>>>,
            isCurrent: Boolean,
        ) {
            val nanos = Instant.parse(statistics).toEpochMilliseconds() * 1_000_000L
            exec(
                driver,
                "INSERT INTO posting_set (posting_set_id, ledger_id) VALUES ('$postingSetId', '$l')",
            )
            exec(
                driver,
                "INSERT INTO transaction_version (version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) " +
                    "VALUES ('$versionId', '$id', '$l', $versionNumber, '$postingSetId', '$occurred', '$statistics', '$statistics', ${versionNote?.let { "'$it'" } ?: "NULL"}, $nanos)",
            )
            legs.forEachIndexed { index, (accountId, amountCurrency) ->
                val (amountMinor, currency) = amountCurrency
                val (code, precision) = currency.split(":")
                exec(
                    driver,
                    "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                        "VALUES ('p-$versionId-$index', '$postingSetId', '$l', $index, '$accountId', $amountMinor, '$code', $precision)",
                )
            }
            if (isCurrent) {
                exec(
                    driver,
                    "INSERT INTO ledger_transaction_current_version (transaction_id, ledger_id, current_version_id) VALUES ('$id', '$l', '$versionId')",
                )
            }
        }
        if (priorLegs != null) {
            insertVersion("v-$id-1", 1, "ps-$id-1", priorVersionNote, priorLegs, isCurrent = false)
            insertVersion("v-$id-2", 2, "ps-$id-2", note, legs, isCurrent = true)
        } else {
            insertVersion("v-$id-1", 1, "ps-$id-1", note, legs, isCurrent = true)
        }
        if (voided) {
            exec(
                driver,
                "INSERT INTO transaction_void_request (ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) " +
                    "VALUES ('$l', 'req-void-$id', '$id', 'void', 'duplicate_entry', NULL, 'explicit_manual_save')",
            )
            exec(
                driver,
                "INSERT INTO transaction_void_fact (ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                    "VALUES ('$l', '$id', 1, 'fact-void-$id', 'void', 'duplicate_entry', NULL, 'req-void-$id', 'conf-void-$id', '$statistics')",
            )
        }
    }

    private fun insertManualReceipt(
        driver: SqlDriver,
        l: String,
        chain: String,
        confirmationId: String,
        transactionId: String,
    ) {
        val table =
            when (chain) {
                "expense" -> "confirmed_expense_receipt"
                "income" -> "confirmed_income_receipt"
                "transfer" -> "confirmed_transfer_receipt"
                "lending" -> "confirmed_lending_receipt"
                else -> throw IllegalArgumentException(chain)
            }
        // The receipt tables carry FKs to the manual request tables; seed the full request row
        // first (the NOT NULL columns and the marker CHECK need the canonical values). The
        // lending chain additionally needs its counterparty row (FK on the receivable account;
        // the request FK is deferred, so the counterparty may land before the receipt either way).
        if (chain == "lending") {
            exec(
                driver,
                "INSERT INTO counterparty (ledger_id, counterparty_id, current_name, receivable_account_id, active) " +
                    "VALUES ('$l', 'seed-counterparty', '种子对手方', 'seed-acc-receivable', 1)",
            )
        }
        val requestId = "req-$chain-$transactionId"
        when (chain) {
            "expense" ->
                exec(
                    driver,
                    "INSERT INTO manual_expense_request (ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker) " +
                        "VALUES ('$l', '$requestId', 100, 'CNY', 2, 'seed-cat-food-leaf', 'seed-acc-bank', '2026-01-15T02:14:40Z', '', 'explicit_manual_save')",
                )
            "income" ->
                exec(
                    driver,
                    "INSERT INTO manual_income_request (ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, receiving_account_id, occurred_at, note, confirmation_marker) " +
                        "VALUES ('$l', '$requestId', 100, 'CNY', 2, 'seed-cat-salary-leaf', 'seed-acc-bank', '2026-01-20T02:30:00Z', '', 'explicit_manual_save')",
                )
            "transfer" ->
                exec(
                    driver,
                    "INSERT INTO manual_transfer_request (ledger_id, request_id, source_account_id, destination_account_id, destination_credit_minor, currency_code, currency_precision, fee_minor, fee_category_id, occurred_at, note, confirmation_marker) " +
                        "VALUES ('$l', '$requestId', 'seed-acc-bank', 'seed-acc-wallet', 100, 'CNY', 2, 0, NULL, '2026-01-21T03:00:00Z', '', 'explicit_manual_save')",
                )
            "lending" ->
                exec(
                    driver,
                    "INSERT INTO manual_lending_request (ledger_id, request_id, behavior_code, counterparty_id, principal_account_id, amount_minor, interest_minor, fee_minor, total_received_minor, interest_category_id, currency_code, currency_precision, occurred_at, note, confirmation_marker) " +
                        "VALUES ('$l', '$requestId', 'LEND', 'seed-counterparty', 'seed-acc-bank', 100, 0, 0, NULL, NULL, 'CNY', 2, '2026-01-22T03:00:00Z', '', 'explicit_manual_save')",
                )
        }
        exec(
            driver,
            "INSERT INTO $table (ledger_id, request_id, confirmation_id, transaction_id) VALUES ('$l', '$requestId', '$confirmationId', '$transactionId')",
        )
    }

    /** Seeds the import-spine lineage chain for one confirmed import creation. */

    private fun insertImportLineage(
        driver: SqlDriver,
        l: String,
        confirmationId: String,
        transactionId: String,
    ) {
        exec(driver, "INSERT INTO import_request (ledger_id, request_id, operation) VALUES ('$l', 'req-import-1', 'confirm_candidate')")
        // valid_complete carries the full fact tuple the source-record CHECK mandates
        // (amount/currency/precision/occurred_at/direction/status all NOT NULL).
        exec(
            driver,
            "INSERT INTO import_source_record (ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, " +
                "amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) " +
                "VALUES ('$l', 'seed-source-1', 'req-import-1', 'seed-input-ref-1', 0, 'ordinary_flow_source', 'sha256:seed-1', 1, 'valid_complete', " +
                "3_333, 'CNY', 2, '2026-01-31T07:33:20Z', 'out', 'settled')",
        )
        exec(
            driver,
            "INSERT INTO import_evidence (ledger_id, evidence_id, source_id, evidence_kind, observed_at) " +
                "VALUES ('$l', 'seed-evidence-1', 'seed-source-1', 'source_observation', '2026-01-31T08:00:00Z')",
        )
        exec(
            driver,
            "INSERT INTO import_candidate (ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) " +
                "VALUES ('$l', 'seed-candidate-1', 'seed-source-1', 'ordinary_flow', '0.9', 'seed-rule', 1)",
        )
        exec(
            driver,
            "INSERT INTO import_candidate_requires_confirmation (ledger_id, candidate_id, requirement_index, requirement) " +
                "VALUES ('$l', 'seed-candidate-1', 0, 'formal_transaction_creation')",
        )
        // The status history walks the spine state machine: the FIRST row must be
        // pending_confirmation, the second confirmed (the transition trigger aborts a direct
        // confirmed insert). Each row carries its own request/status ids (both UNIQUE).
        exec(
            driver,
            "INSERT INTO import_request (ledger_id, request_id, operation) VALUES ('$l', 'req-import-intake', 'intake')",
        )
        exec(
            driver,
            "INSERT INTO import_candidate_status_history (ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) " +
                "VALUES ('$l', 'seed-candidate-1', 1, 'seed-status-pending', 'pending_confirmation', 'req-import-intake', 'status_transition')",
        )
        exec(
            driver,
            "INSERT INTO import_candidate_status_history (ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) " +
                "VALUES ('$l', 'seed-candidate-1', 2, 'seed-status-1', 'confirmed', 'req-import-1', 'creation')",
        )
        exec(
            driver,
            "INSERT INTO import_candidate_decision_snapshot (ledger_id, request_id, decision, candidate_id, expected_content_hash, category_id, funding_account_id) " +
                "VALUES ('$l', 'req-import-1', 'confirm', 'seed-candidate-1', 'sha256:seed-1', 'seed-cat-food-leaf', 'seed-acc-bank')",
        )
        exec(
            driver,
            "INSERT INTO import_confirmation (ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class, confirmed_at) " +
                "VALUES ('$l', '$confirmationId', 'req-import-1', 'seed-candidate-1', 'seed-status-1', '$transactionId', 'creation', '2026-01-31T08:30:00Z')",
        )
    }

    private fun exec(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.execute(null, sql, 0)
    }
}
