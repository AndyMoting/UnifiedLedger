package com.unifiedledger.application.import.ccb

/**
 * BP-01 (D-116) CCB online-banking XLS frozen token tables (spec sections 2.2, 3.2).
 *
 * Every token below is a frozen contract constant: byte-level header match at 0-based
 * row 3, the accepted ordinary/transfer summary sets, the refund marker, the 钞汇
 * value domain, the frozen evidence constants (+08:00 offset, CNY currency, precision
 * 2, settled status, Sheet0) and the bounded-input limits. The title area (0-based
 * rows 0..2) is never read at all.
 *
 * The routing sets are closed (spec section 3.2): any summary token outside them fails
 * closed to SPINE_CCB_UNKNOWN_TOKEN; expansion only via explicit contract amendment.
 */
object CcbSourceTokens {
    /** Frozen 9-column header (0-based row 3), byte-level exact match, fixed order. */
    val HEADER_TOKENS: List<String> =
        listOf(
            "序号",
            "摘要",
            "币别",
            "钞汇",
            "交易日期",
            "交易金额",
            "账户余额",
            "交易地点/附言",
            "对方账号与户名",
        )

    const val HEADER_ROW_INDEX: Int = 3
    const val FIRST_DATA_ROW_INDEX: Int = 4
    const val FIELD_COUNT: Int = 9
    const val SHEET_NAME: String = "Sheet0"

    /**
     * Frozen ordinary-flow summary domain (spec section 3.2). Direction never comes
     * from the summary token: it always comes from the signed amount (spec 2.3).
     */
    val ORDINARY_SUMMARY_TYPES: Set<String> = setOf("消费", "银联入账")

    /** Frozen self-transfer/self-owned-asset summary set (spec section 3.2). */
    val TRANSFER_SUMMARY_TYPES: Set<String> = setOf("充值", "支付机构提现", "数字人民币兑出")

    /** Refund rows (summary OR remark containing this marker) are rejected (judgment order 1). */
    const val REFUND_MARKER: String = "退款"

    /** Frozen 钞汇 value domain (spec section 2.2); anything outside fails closed. */
    val CURRENCY_KIND_VALUES: Set<String> = setOf("钞", "汇")

    /** Frozen evidence constants (spec sections 2.2/2.3); never read from the file. */
    const val CURRENCY_CNY: String = "CNY"
    const val UTC_OFFSET: String = "+08:00"

    /** Frozen amount precision: exactly two decimal places, a format constant. */
    const val AMOUNT_PRECISION: Int = 2

    /** Frozen bounded-input constants (spec section 2.2; evidence bound plus margin). */
    const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024
    const val MAX_DATA_ROWS: Int = 20_000

    /** Frozen status fact: bank statements are cleared-and-settled by format semantics. */
    const val STATUS_SETTLED: String = "settled"

    /** Frozen provenance rule names (D-097:1455 layered facts; spec section 2.2/2.3). */
    const val CURRENCY_RULE: String = "currency_v1"
    const val OFFSET_RULE: String = "bank_offset_v1"
    const val STATUS_RULE: String = "bank_statement_cleared_v1"
    const val DIRECTION_RULE: String = "amount_sign_direction_v1"

    // Frozen diagnostic field roles (spec section 2.4 safe-location shape).
    const val FIELD_ROLE_OCCURRED_AT: String = "occurred_at"
    const val FIELD_ROLE_AMOUNT: String = "amount"
    const val FIELD_ROLE_DIRECTION: String = "direction"
    const val FIELD_ROLE_STATUS: String = "status"
    const val FIELD_ROLE_BALANCE: String = "balance"
}
