package com.unifiedledger.domain

/**
 * D-084 RG08-GAP-02 lending behavior-code registry. The frozen fixture registers exactly four
 * behavior codes with their principal effect and settlement boolean; the enum itself is the
 * closed registry and [fromCode] is the only entry point for raw code strings.
 */
enum class LendingPrincipalEffect(
    val code: String,
) {
    INCREASE_PAYABLE("increase_payable"),
    INCREASE_RECEIVABLE("increase_receivable"),
    DECREASE_RECEIVABLE("decrease_receivable"),
    DECREASE_PAYABLE("decrease_payable"),
}

enum class LendingBehaviorCode(
    val code: String,
    val principalEffect: LendingPrincipalEffect,
    val settlement: Boolean,
) {
    BORROW("borrow", LendingPrincipalEffect.INCREASE_PAYABLE, false),
    LEND("lend", LendingPrincipalEffect.INCREASE_RECEIVABLE, false),
    COLLECT("collect", LendingPrincipalEffect.DECREASE_RECEIVABLE, true),
    REPAY("repay", LendingPrincipalEffect.DECREASE_PAYABLE, true),
    ;

    companion object {
        fun fromCode(code: String): LendingBehaviorCode? = entries.firstOrNull { it.code == code }
    }
}
