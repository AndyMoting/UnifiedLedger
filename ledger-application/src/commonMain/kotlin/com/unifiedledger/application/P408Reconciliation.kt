package com.unifiedledger.application

/** The two approved evidence responsibilities; channel/source names are not duties. */
enum class P408EvidenceResponsibility(val storageValue: String) {
    REAL_ACCOUNT_POSTING("real_account_posting"),
    DESTINATION_ASSET_POSTING("destination_asset_posting"),
}

/** Product reconciliation statuses mapped from storage tokens to approved labels. */
enum class P408ReconciliationStatus(val storageValue: String, val label: String) {
    PENDING("PENDING", "待对账"),
    PARTIAL("PARTIAL", "部分匹配"),
    DIFFERENCE("DIFFERENCE", "有差异"),
    MISSING("MISSING", "待补资料"),
    CHECKED("CHECKED", "已核对");

    companion object {
        fun fromStorage(value: String): P408ReconciliationStatus =
            values().first { it.storageValue == value }
    }
}

data class P408ConfirmLinkRequest(
    val ledgerId: String,
    val requestId: String,
    val evidenceId: String,
    val candidateId: String,
    val postingId: String,
    val transactionId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Int,
    val direction: String,
    val accountId: String,
    val responsibility: P408EvidenceResponsibility,
    val basisVersion: Int,
    val matchBasis: Set<String>,
    val windowDays: Int,
    val naturalDayDistance: Int,
    val sourceOccurredAt: String,
    val confirmedAt: String,
    val linkId: String,
    val reconciliationId: String,
    val createdAt: String,
) {
    init {
        require(ledgerId.isNotBlank() && requestId.isNotBlank())
        require(evidenceId.isNotBlank() && candidateId.isNotBlank() && postingId.isNotBlank() && transactionId.isNotBlank())
        require(currencyCode.isNotBlank() && currencyPrecision >= 0)
        require(direction == "in" || direction == "out")
        require(accountId.isNotBlank())
        require(basisVersion == 1)
        require(matchBasis == REQUIRED_MATCH_BASIS)
        require(amountMinor >= 0)
        require(naturalDayDistance >= 0 && naturalDayDistance <= windowDays)
        require(sourceOccurredAt.isNotBlank() && confirmedAt.isNotBlank() && createdAt.isNotBlank())
        require(linkId.isNotBlank() && reconciliationId.isNotBlank())
    }

    /** Stable UTF-8 identity; set-valued basis tokens are sorted and deduplicated. */
    fun fingerprint(): String = buildString {
        append("p408-confirm-v1|")
        append("ledger=").append(ledgerId).append('|')
        append("evidence=").append(evidenceId).append('|')
        append("candidate=").append(candidateId).append('|')
        append("posting=").append(postingId).append('|')
        append("transaction=").append(transactionId).append('|')
        append("amount_minor=").append(amountMinor).append('|')
        append("currency=").append(currencyCode).append('|')
        append("precision=").append(currencyPrecision).append('|')
        append("direction=").append(direction).append('|')
        append("account=").append(accountId).append('|')
        append("responsibility=").append(responsibility.storageValue).append('|')
        append("basis_version=").append(basisVersion).append('|')
        append("basis=").append(matchBasis.toSortedSet().joinToString(",")).append('|')
        append("window_days=").append(windowDays).append('|')
        append("natural_day_distance=").append(naturalDayDistance).append('|')
        append("source_occurred_at=").append(sourceOccurredAt).append('|')
        append("confirmed_at=").append(confirmedAt)
    }
}

data class P408ReconciliationReceipt(
    val requestId: String,
    val outcome: String,
    val linkId: String?,
    val reconciliationId: String?,
    val historySequence: Long?,
)

sealed interface P408ReconciliationResult {
    data class Accepted(val receipt: P408ReconciliationReceipt) : P408ReconciliationResult
    data class NoChange(val receipt: P408ReconciliationReceipt) : P408ReconciliationResult
    data class Rejected(val code: String) : P408ReconciliationResult
}

interface P408ReconciliationCommitPort {
    fun confirmLink(request: P408ConfirmLinkRequest): P408ReconciliationResult
}

data class P408ReconciliationReportRow(
    val postingId: String,
    val transactionId: String,
    val accountId: String,
    val status: P408ReconciliationStatus,
    val activeLinkIds: List<String>,
)

interface P408ReconciliationReadPort {
    fun readReconciliationReport(ledgerId: String): List<P408ReconciliationReportRow>
}

private val REQUIRED_MATCH_BASIS = setOf(
    "amount",
    "currency",
    "direction",
    "account",
    "occurred_at_window",
)
