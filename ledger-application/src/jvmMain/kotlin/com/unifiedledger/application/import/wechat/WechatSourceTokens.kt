package com.unifiedledger.application.import.wechat

/**
 * P4-03 frozen token tables (spec sections 2.2, 2.3 and 3).
 *
 * Every token below is a frozen contract constant: byte-level header match, the
 * accepted/rejected transaction-type sets, the direction mapping, the settled-status
 * subset and the frozen evidence constants (+08:00 offset, CNY currency). The parser
 * never reads a timezone or currency declaration from any region of the input file.
 */
object WechatSourceTokens {
    /** Frozen 11-column header (0-based row 17), byte-level exact match. */
    val HEADER_TOKENS: List<String> = listOf(
        "交易时间", "交易类型", "交易对方", "商品", "收/支",
        "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注",
    )

    const val HEADER_ROW_INDEX: Int = 17
    const val FIRST_DATA_ROW_INDEX: Int = 18

    /** Frozen ordinary-flow type domain (spec section 3). */
    val ACCEPTED_TX_TYPES: Set<String> = setOf("商户消费", "扫二维码付款", "二维码收款", "赞赏码", "其他")

    /** Frozen out-of-scope type set: typed rejection, registered for later batches. */
    val REJECTED_TX_TYPES: Set<String> = setOf("转账", "群收款", "零钱提现", "零钱充值", "微信红包")

    /** Refund rows (type or status token containing this marker) are rejected (judgment order 1). */
    const val REFUND_MARKER: String = "退款"

    /** Frozen direction mapping; any other token keeps its raw value and stays unresolved. */
    val DIRECTION_TOKEN_MAP: Map<String, String> = mapOf("收入" to "in", "支出" to "out")

    /** Frozen settled-status subset; every other status token stays raw + unresolved. */
    val SETTLED_STATUS_TOKENS: Set<String> = setOf("支付成功", "已存入零钱", "已到账")
    const val STATUS_SETTLED: String = "settled"

    /** Frozen evidence constants (D-099:1536); never read from the file at runtime. */
    const val CURRENCY_CNY: String = "CNY"
    const val UTC_OFFSET: String = "+08:00"

    /** Frozen bounded-input constants (spec section 1.1; evidence bound plus margin). */
    const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024
    const val MAX_DATA_ROWS: Int = 10_000

    // Frozen diagnostic field roles (spec section 5 safe-location shape).
    const val FIELD_ROLE_OCCURRED_AT: String = "occurred_at"
    const val FIELD_ROLE_AMOUNT: String = "amount"
    const val FIELD_ROLE_DIRECTION: String = "direction"
    const val FIELD_ROLE_STATUS: String = "status"
}
