package com.unifiedledger.application.import.alipay

/**
 * P4-05 + RL-04 (P4-05b) frozen token tables (spec sections 1.1, 2.2, 2.4, 3 and the
 * frozen RL-04 yuebao transfer routing design sections 2.2/2.3/3).
 *
 * Every token below is a frozen contract constant: byte-level header match at 0-based
 * line 23, the accepted/rejected category sets, the refund marker, the direction
 * mapping, the settled-status subset, the frozen evidence constants (+08:00 offset,
 * CNY currency) and the RL-04 yuebao transfer subtype tables. The parser never reads
 * a timezone or currency declaration from any region of the input file (the metadata
 * area at lines 0-22 is never read at all).
 *
 * RL-04 negative registrations (frozen in the design matrix, section 3; deliberately
 * NOT part of [YUEBAO_TRANSFER_SUBTYPES]): the parser routes a 投资理财 row to the
 * transfer flow only when its 商品说明 exactly matches a frozen subtype AND its status
 * is 交易成功. Registered non-routed families fail closed to SPINE_ALIPAY_UNKNOWN_TOKEN:
 *   - 余额宝-单次转入  — not frozen (the only real sample is 交易关闭; awaiting a real
 *     success sample; if its 收/付款方式 proves bank-card it would be a missing-leg case).
 *   - 余额宝-转出到银行卡 — not frozen (missing-leg registration: the destination is an
 *     external card, not a self-owned asset account).
 *   - 余额宝-收益发放   — hard negative registration: income (RL-05), TransferFlow routing
 *     forbidden; must never enter the transfer route.
 * These three tokens must never be added to [YUEBAO_TRANSFER_SUBTYPES] unless a future
 * contract amendment explicitly freezes them.
 */
object AlipaySourceTokens {
    /** Frozen 12-column header (0-based line 23), byte-level exact match, fixed order.
     *  Canonical real tokens (spec §9.2, byte-verified across 9 real exports on 2026-08-18):
     *  index 0 交易时间=occurred_at, 1 交易分类=route, 2 交易对方, 3 对方账号, 4 商品说明,
     *  5 收/支=direction, 6 金额=amount, 7 收/付款方式, 8 交易状态=status, 9 交易订单号,
     *  10 商家订单号, 11 备注. Columns 2/3/4/7/9/10/11 are never persisted. */
    val HEADER_TOKENS: List<String> = listOf(
        "交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支",
        "金额", "收/付款方式", "交易状态", "交易订单号", "商家订单号", "备注",
    )

    const val HEADER_ROW_INDEX: Int = 23
    const val FIRST_DATA_ROW_INDEX: Int = 24

    /**
     * Header and every data row carry a trailing comma: 12 columns + trailing comma =
     * 13 comma-separated fields, the 13th field always empty.
     */
    const val FIELD_COUNT: Int = 13

    /** Frozen ordinary-flow category domain (spec section 3.2); expansion only via explicit contract amendment. */
    val ACCEPTED_TX_TYPES: Set<String> = setOf("网上支付", "扫码支付", "其他")

    /** Frozen out-of-scope category set: typed rejection, registered per family for later batches (spec section 3.2).
     *  P4-06 slice 1 (D-107 section 2.3): 信用借还 moved out of this set into its own
     *  dedicated judgment-order-3 branch (credit repayment routing). */
    val REJECTED_TX_TYPES: Set<String> = setOf("账户存取", "转账红包", "亲友代付")

    /** P4-06 slice 1 (D-107 section 2.3): the 信用借还 family category token. */
    const val CREDIT_TX_TYPE: String = "信用借还"

    /** Refund rows (category or status token containing this marker) are rejected (judgment order 1). */
    const val REFUND_MARKER: String = "退款"

    /** Frozen direction mapping; any other token keeps its raw value and stays unresolved. */
    val DIRECTION_TOKEN_MAP: Map<String, String> = mapOf("收入" to "in", "支出" to "out")

    /** The frozen neutral direction token (P4-05 section 3.1; the refund and credit-family gates read it). */
    const val NEUTRAL_DIRECTION_TOKEN: String = "不计收支"

    /** Frozen settled-status mapping subset (spec section 2.4); every other status token stays raw + unresolved. */
    const val STATUS_SETTLED: String = "settled"
    val STATUS_TOKEN_MAP: Map<String, String> = mapOf("交易成功" to STATUS_SETTLED)

    /**
     * RL-04 frozen route category (design section 2.3, evidence 9/9 real samples): every
     * 余额宝 行 carries 交易分类 = 投资理财. The yuebao transfer branch triggers on exactly
     * this category token; for every other category 商品说明 (field 4) is never read.
     */
    const val INVESTMENT_CATEGORY: String = "投资理财"

    /**
     * RL-04 frozen transfer subtype collection (design sections 2.2/3). Exact-match only,
     * and only for 交易分类=投资理财 rows; any other 商品说明 value (empty, unknown, or one of
     * the registered non-frozen families above) stays fail-closed SPINE_ALIPAY_UNKNOWN_TOKEN.
     */
    val YUEBAO_TRANSFER_SUBTYPES: Set<String> = setOf("余额宝-自动转入", "余额宝-转出到余额")

    /**
     * RL-04 frozen subtype -> direction mapping (design section 2.2). 余额宝-自动转入 =
     * 余额 -> 余额宝 (wallet is the FROM leg; "out"); 余额宝-转出到余额 = 余额宝 -> 余额 (wallet
     * is the TO leg; "in"). The 收/支 column (field 5) is never read for a routed row:
     * direction is derived only from this exact mapping (P4-04 wechat token-family
     * precedent), provenance rule [YUEBAO_SUBTYPE_DIRECTION_RULE] with exact confidence.
     */
    val YUEBAO_SUBTYPE_DIRECTION_MAP: Map<String, String> = mapOf(
        "余额宝-自动转入" to "out",
        "余额宝-转出到余额" to "in",
    )

    /** RL-04 frozen provenance rule for the subtype-derived direction fact (design §2.2, D-097:1455). */
    const val YUEBAO_SUBTYPE_DIRECTION_RULE: String = "yuebao_subtype_direction_v1"

    // ---- P4-06 slice 1 (RL-05 credit, D-107 section 2): payment-leg whitelist ----
    //
    // Column 7 (收/付款方式, 0-based field 7) is a composite token: legs joined by '&',
    // each leg shaped "token(annotation)". Only the normalized whitelist tokens below
    // are ever routed or persisted; masked tails, accounts and raw annotations never
    // leave the parser (privacy boundary). Expansion only via explicit contract
    // amendment; the parser never accepts a new token silently.

    /** Frozen first-batch credit leg (D-107 section 2.1; 花呗分期(N期) folds onto this token). */
    val CREDIT_LEG_TOKENS: Set<String> = setOf("花呗")

    /** Frozen first-batch asset legs (D-107 section 2.1). */
    val ASSET_LEG_TOKENS: Set<String> = setOf("余额宝", "账户余额", "余额", "招商银行储蓄卡")

    /** Exactly a 4-digit numeric mask tail, e.g. 招商银行储蓄卡(0123) (D-107 section 2.2). */
    val LEG_TAIL_4_DIGIT_MASK: Regex = Regex("\\(\\d{4}\\)$")

    /** The frozen qualifier tail (个人余额) (D-107 section 2.2). */
    const val LEG_TAIL_PERSONAL_QUALIFIER: String = "(个人余额)"

    /** The frozen installment family form 花呗分期(N期) folding onto the 花呗 credit leg (D-107 section 2.2). */
    val LEG_TAIL_INSTALLMENT_FORM: Regex = Regex("^花呗分期\\(\\d+期\\)$")

    /** Frozen family status gates (D-107 section 2.5): credit consumption/repayment/refund success tokens. */
    const val CREDIT_EXPENSE_STATUS_SUCCESS: String = "交易成功"
    const val CREDIT_REPAYMENT_STATUS_SUCCESS: String = "还款"
    const val CREDIT_REFUND_STATUS_SUCCESS: String = "退款成功"

    /** Frozen family status persistence values (D-107 section 2.5); refund_settled is the
     *  fact-layer discriminant of the credit refund variant. */
    const val STATUS_SETTLED_VALUE: String = "settled"
    const val STATUS_REFUND_SETTLED_VALUE: String = "refund_settled"

    /** Frozen direction-derivation rules (D-107 section 2.5): repayment -> out, refund -> in. */
    const val CREDIT_REPAYMENT_DIRECTION_RULE: String = "credit_repayment_direction_v1"
    const val CREDIT_REFUND_DIRECTION_RULE: String = "credit_refund_direction_v1"

    /**
     * D-107 section 2.2 frozen bracket stripping: exactly one tail annotation of the
     * three frozen forms is removed (a 4-digit numeric mask, the (个人余额) qualifier, or
     * the 花呗分期(N期) installment form which folds onto the 花呗 credit-leg token); an
     * unbracketed token stays as-is. The stripped result must literally equal a
     * whitelist member (any other bracket shape, any inner bracket, or any other token
     * text fails). Returns the normalized whitelist token, or null for a non-whitelist
     * leg. Never returns or leaks the raw leg text.
     */
    fun normalizePaymentLegToken(rawLeg: String): String? {
        val stripped = when {
            LEG_TAIL_INSTALLMENT_FORM.matches(rawLeg) -> "花呗"
            LEG_TAIL_4_DIGIT_MASK.containsMatchIn(rawLeg) -> rawLeg.dropLast(6)
            rawLeg.endsWith(LEG_TAIL_PERSONAL_QUALIFIER) -> rawLeg.dropLast(LEG_TAIL_PERSONAL_QUALIFIER.length)
            else -> rawLeg
        }
        return if (stripped in CREDIT_LEG_TOKENS || stripped in ASSET_LEG_TOKENS) stripped else null
    }

    /** Frozen evidence constants (D-099:1536); never read from the file at runtime. */
    const val CURRENCY_CNY: String = "CNY"
    const val UTC_OFFSET: String = "+08:00"

    /** Frozen amount precision: exactly two decimal places, a format constant never derived per row. */
    const val AMOUNT_PRECISION: Int = 2

    /** Frozen bounded-input constants (spec section 1.1; evidence bound plus margin). */
    const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024
    const val MAX_DATA_ROWS: Int = 20_000

    // Frozen diagnostic field roles (spec section 4 safe-location shape).
    const val FIELD_ROLE_OCCURRED_AT: String = "occurred_at"
    const val FIELD_ROLE_AMOUNT: String = "amount"
    const val FIELD_ROLE_DIRECTION: String = "direction"
    const val FIELD_ROLE_STATUS: String = "status"
}
