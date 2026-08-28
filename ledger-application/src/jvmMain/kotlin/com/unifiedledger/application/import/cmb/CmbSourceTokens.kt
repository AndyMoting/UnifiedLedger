package com.unifiedledger.application.import.cmb

/**
 * BP-01 (D-116) CMB online-banking CSV frozen token tables (spec sections 2.1, 3.1).
 *
 * Every token below is a frozen contract constant: byte-level header match at 0-based
 * row 7, the accepted ordinary/transfer type sets, the refund marker, the frozen
 * evidence constants (+08:00 offset, CNY currency, precision 2, settled status) and
 * the bounded-input limits. The parser never reads a timezone or currency declaration
 * from any region of the input file; the comment block (0-based rows 0..5) and the
 * empty row 6 are never read at all.
 *
 * The routing sets are closed (spec section 3.1): any token outside them fails closed
 * to SPINE_CMB_UNKNOWN_TOKEN; expansion only via explicit contract amendment.
 */
object CmbSourceTokens {
    /** Frozen 7-column header (0-based row 7), byte-level exact match, fixed order. */
    val HEADER_TOKENS: List<String> =
        listOf(
            "交易日期",
            "交易时间",
            "收入",
            "支出",
            "余额",
            "交易类型",
            "交易备注",
        )

    const val HEADER_ROW_INDEX: Int = 7
    const val FIRST_DATA_ROW_INDEX: Int = 8
    const val FIELD_COUNT: Int = 7

    /**
     * Frozen ordinary-flow type domain (spec section 3.1). Direction never comes from
     * the type token: it always comes from the 收入/支出 columns (spec section 2.3).
     */
    val ORDINARY_TX_TYPES: Set<String> =
        setOf(
            "网联协议支付",
            "银联快捷支付",
            "网联付款交易",
            "银联代付",
            "银联在线支付",
            "数字人民币随用随充消费",
            "支付鼓励金",
            "账户结息",
            "汇入汇款",
        )

    /** Frozen self-transfer/self-owned-asset type set (spec section 3.1). */
    val TRANSFER_TX_TYPES: Set<String> =
        setOf(
            "数字人民币充值",
            "数字人民币存银行",
            "朝朝宝购买",
            "朝朝宝赎回",
        )

    /** Refund rows (type token containing this marker) are rejected (judgment order 1). */
    const val REFUND_MARKER: String = "退款"

    /** Frozen evidence constants (spec sections 2.1/2.3); never read from the file. */
    const val CURRENCY_CNY: String = "CNY"
    const val UTC_OFFSET: String = "+08:00"

    /** Frozen amount precision: exactly two decimal places, a format constant. */
    const val AMOUNT_PRECISION: Int = 2

    /** Frozen bounded-input constants (spec section 2.1; evidence bound plus margin). */
    const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024
    const val MAX_DATA_ROWS: Int = 20_000

    /** Frozen status fact: bank statements are cleared-and-settled by format semantics. */
    const val STATUS_SETTLED: String = "settled"

    /** Frozen provenance rule names (D-097:1455 layered facts; spec section 2.1/2.3). */
    const val CURRENCY_RULE: String = "currency_v1"
    const val OFFSET_RULE: String = "bank_offset_v1"
    const val STATUS_RULE: String = "bank_statement_cleared_v1"

    // Frozen diagnostic field roles (spec section 2.4 safe-location shape).
    const val FIELD_ROLE_OCCURRED_AT: String = "occurred_at"
    const val FIELD_ROLE_AMOUNT: String = "amount"
    const val FIELD_ROLE_DIRECTION: String = "direction"
    const val FIELD_ROLE_STATUS: String = "status"
    const val FIELD_ROLE_BALANCE: String = "balance"
}
