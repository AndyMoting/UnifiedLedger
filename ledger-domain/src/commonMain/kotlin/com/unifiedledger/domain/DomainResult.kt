package com.unifiedledger.domain

sealed interface DomainResult<out T> {
    data class Success<out T>(val value: T) : DomainResult<T>

    data class Failure(val violation: DomainViolation) : DomainResult<Nothing>
}

sealed interface DomainViolation {
    data object UnbalancedPostingSet : DomainViolation

    data object InvalidPostingSet : DomainViolation

    data object ArithmeticOverflow : DomainViolation

    data object InvalidCatalog : DomainViolation

    data object InvalidFormalTransaction : DomainViolation

    data object InvalidOrdinaryExpense : DomainViolation

    data object InvalidBalanceReplay : DomainViolation
}
