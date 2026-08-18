package com.unifiedledger.application.import.alipay

/**
 * P4-05 frozen token tables (spec sections 1.1, 2.2, 2.4 and 3).
 *
 * Every token below is a frozen contract constant: byte-level header match at 0-based
 * line 23, the accepted/rejected category sets, the refund marker, the direction
 * mapping, the settled-status subset and the frozen evidence constants (+08:00 offset,
 * CNY currency). The parser never reads a timezone or currency declaration from any
 * region of the input file (the metadata area at lines 0-22 is never read at all).
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

    /** Frozen out-of-scope category set: typed rejection, registered per family for later batches (spec section 3.2). */
    val REJECTED_TX_TYPES: Set<String> = setOf("账户存取", "转账红包", "亲友代付", "信用借还")

    /** Refund rows (category or status token containing this marker) are rejected (judgment order 1). */
    const val REFUND_MARKER: String = "退款"

    /** Frozen direction mapping; any other token keeps its raw value and stays unresolved. */
    val DIRECTION_TOKEN_MAP: Map<String, String> = mapOf("收入" to "in", "支出" to "out")

    /** Frozen settled-status mapping subset (spec section 2.4); every other status token stays raw + unresolved. */
    const val STATUS_SETTLED: String = "settled"
    val STATUS_TOKEN_MAP: Map<String, String> = mapOf("交易成功" to STATUS_SETTLED)

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
