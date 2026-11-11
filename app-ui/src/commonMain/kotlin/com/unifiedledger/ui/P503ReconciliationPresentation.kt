package com.unifiedledger.ui

import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801BindEvidenceRefusal
import com.unifiedledger.application.P801CandidateProposal
import com.unifiedledger.application.P801CandidateProposalFailure
import com.unifiedledger.application.P801ConfirmLinkRefusal
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegEvidenceLink
import com.unifiedledger.application.P801ReconciledLegLinkHistoryEntry
import com.unifiedledger.application.P801ReconciledLegStatusEntry
import com.unifiedledger.application.P801ReconciliationCandidateEvidence
import com.unifiedledger.domain.CurrencyUnit

/*
 * P8-01a i3a reconciliation presentation (D-308; spec sections 2.2/2.3/2.4/2.5/2.6/2.7).
 *
 * Every user-visible string of the reconciliation surface lives here as a pure function of the
 * application-layer payload, so the frozen copy is unit-testable without a Compose harness
 * (D-308 / CONTRIBUTING:207) and the copy inventory is one file:
 *
 * - the five-state labels come from [P408ReconciliationStatus.label] and are never re-typed;
 * - a `null` status renders 「—（无对账资格）」 and NEVER a five-state label (V-7);
 * - the queue's totals use the spec section 2.3 terms 已核对 / 未核对 / 条数 — 「已对账」 never
 *   appears, and no 「账户余额 − 区间明细合计」 phrasing exists anywhere in this surface (D-308
 *   item 6: that algorithm is rejected; 余额 must not be derived from reconciliation);
 * - a rejection always keeps its raw code token beside the readable label (the `p705NoticeText`
 *   precedent), the three matcher causes are quoted verbatim, and the bind step states its
 *   irreversibility BEFORE the action (spec section 2.4);
 * - nothing here ranks, scores or orders candidates: `confidence` is the matcher's single `EXACT`
 *   value and the list order is the use case's frozen presentation order (spec section 2.4).
 */

/** The entry title shared by the account-flow action and the queue header (spec section 2.2). */
internal const val RECONCILIATION_TITLE: String = "账户对账"

/** The evidence-focused entry's title (spec section 2.2 entry B, verbatim). */
internal const val RECONCILIATION_EVIDENCE_ENTRY_TITLE: String = "用于核对既有分录"

/**
 * The derived import-row marker of spec section 2.7 (D-308 item 4): an active `evidence_link`
 * exists, so this import candidate is already used to reconcile an existing entry.
 */
internal const val IMPORT_USED_FOR_RECONCILIATION_TEXT: String = "已用于核对既有分录"

/** The bind step's irreversibility warning, shown BEFORE the action (spec section 2.4, verbatim). */
internal const val RECONCILIATION_BIND_IRREVERSIBLE_WARNING: String = "绑定后不能在应用内改绑"

/** The non-confirmable leg's honest action copy (spec section 2.1, verbatim). */
internal const val RECONCILIATION_NOT_CONFIRMABLE_TEXT: String = "暂不支持在应用内核对"

/** The typed `null` eligibility copy (spec section 4 V-7, verbatim). */
internal const val RECONCILIATION_NO_ELIGIBILITY_TEXT: String = "—（无对账资格）"

/**
 * P8-01a i3a fix round F5: the audit's "this leg has no `posting_reconciliation` row" copy. It is
 * deliberately DIFFERENT from [RECONCILIATION_NO_ELIGIBILITY_TEXT]: a merely-unreconciled yet
 * eligible leg is `PENDING` in the queue and must never be described as ineligible. The
 * ineligibility copy stays reserved for a leg the eligibility derivation actually excluded.
 */
internal const val RECONCILIATION_NO_RECONCILIATION_TEXT: String = "—（尚无对账记录）"

/** The audit's "a reconciliation row exists but its history is empty" copy (honest absence). */
internal const val RECONCILIATION_NO_STATUS_HISTORY_TEXT: String = "—（尚无状态历史）"

/** The queue row's click action label (F12f: an ACTION, not a screen name). */
internal const val RECONCILIATION_OPEN_LEG_LABEL: String = "打开该分录的核对详情"

/** The result-unknown notice (spec section 2.5, verbatim). */
internal const val RECONCILIATION_UNKNOWN_RESULT_TEXT: String = "结果未知，重新进入核对"

/** The confirm step's four elements (spec section 2.5, verbatim). */
internal const val RECONCILIATION_ELEMENT_EXTERNAL_EVIDENCE: String = "外部证据"
internal const val RECONCILIATION_ELEMENT_RESPONSIBILITY: String = "证据职责"
internal const val RECONCILIATION_ELEMENT_MATCH_BASIS: String = "匹配依据"
internal const val RECONCILIATION_ELEMENT_HUMAN_DECISION: String = "人工决定"

/**
 * The persisted `human_decision` token of a confirm (the schema's only admitted value; spec section
 * 2.5 fixes the human decision to the explicit confirmation). Rendered as the raw token — the
 * surface never translates a persisted token into a friendlier but unverifiable word.
 */
internal const val RECONCILIATION_HUMAN_DECISION_CONFIRM_MATCH: String = "confirm_match"

/** Section headers of the candidate/audit surface (spec sections 2.4/2.6). */
internal const val RECONCILIATION_CANDIDATES_HEADER: String = "候选证据"
internal const val RECONCILIATION_AUDIT_HEADER: String = "审计回看"
internal const val RECONCILIATION_HISTORY_HEADER: String = "状态历史"

// ------------------------------------------------------------------ queue face and partition copy

/** Which body the queue renders (the account-flow body-face precedent). */
internal enum class ReconciliationQueueFace {
    /** The row read failed and no row is loaded: the failure face with its own retry (V-9). */
    UNAVAILABLE,

    /** No reconcilable leg at all — an honest empty queue; the computed totals still render (V-9). */
    EMPTY,

    /** The list face (also the zero-row open-keyset shape, never an empty claim). */
    LIST,
}

/**
 * The queue body decision. A first-page failure outranks the empty copy (a typed failure never
 * degrades into 「没有可对账的分录」), and the empty face requires BOTH no loaded row and an
 * exhausted keyset with no failure — an open keyset never reads as empty.
 */
internal fun reconciliationQueueFace(state: P503AppState.ReconciliationQueue): ReconciliationQueueFace =
    when {
        state.rowsLoadFailed && state.rows.isEmpty() -> ReconciliationQueueFace.UNAVAILABLE
        state.rows.isEmpty() && state.nextCursor == null && !state.rowsLoadFailed -> ReconciliationQueueFace.EMPTY
        else -> ReconciliationQueueFace.LIST
    }

/** The honest empty copy of the queue (spec section 2.3: the account has no reconcilable leg). */
internal fun reconciliationQueueEmptyText(): String = "该账户没有可对账的分录。"

/** The row read's failure copy (spec section 2.3 / V-9: explicit failure, loaded rows kept). */
internal fun reconciliationQueueFailureText(): String = "队列读取失败，可点击重试。"

/** The summary read's failure copy (spec section 2.3 / V-9: never a zero total, rows kept). */
internal fun reconciliationSummaryFailureText(): String = "合计读取失败，队列条目保留。"

/** The 未核对 partition header (spec section 2.3). */
internal fun reconciliationUncheckedPartitionText(uncheckedCount: Int): String = "未核对 $uncheckedCount 条"

/**
 * The 已核对 partition header, collapsed by default (spec section 2.3: 已核对分录收在独立分区、
 * 默认折叠、按需展开). 展开 is the spec's own word; 收起 is its necessary inverse for the same control.
 */
internal fun reconciliationCheckedPartitionText(
    checkedCount: Int,
    expanded: Boolean,
): String = "已核对 $checkedCount 条 · " + if (expanded) "收起" else "展开"

// ------------------------------------------------------------------ five-state labels

/**
 * The five-state label of a status, or the typed `null` eligibility copy. [status] is null exactly
 * when the leg has no reconciliation status to show — it must NEVER fall back to a five-state label,
 * least of all `PENDING` (spec section 4, V-7).
 */
internal fun reconciliationStatusText(status: P408ReconciliationStatus?): String = status?.label ?: RECONCILIATION_NO_ELIGIBILITY_TEXT

/**
 * The eligibility line of a candidate leg (spec section 2.4 / V-7): a leg failing the composite
 * mark renders 「—（无对账资格）」; an eligible leg renders nothing here, because this surface reads
 * no reconciliation status for a candidate leg and a not-read status is never presented as one.
 */
internal fun reconciliationCandidateEligibilityText(eligibleRealAccount: Boolean): String? = if (eligibleRealAccount) null else RECONCILIATION_NO_ELIGIBILITY_TEXT

/** The evidence responsibility token, as persisted (spec section 2.5 pins the two duties). */
internal fun reconciliationResponsibilityText(responsibility: P408EvidenceResponsibility): String = responsibility.storageValue

// ------------------------------------------------------------------ queue rows

/** The date of one queue row (the account-flow headline's exact pipeline, no locale formatting). */
internal fun reconciliationRowDateText(row: P801AccountReconciliationQueueRow): String = row.occurredAt.toString().take(10)

/**
 * One queue row's headline (spec section 2.3: 日期、备注或交易类型、该腿精确带符号金额与币种).
 * The amount is the leg's exact signed minor units through [formatMinorUnits] — never a net, never
 * a float.
 */
internal fun reconciliationQueueRowText(row: P801AccountReconciliationQueueRow): String {
    val head = reconciliationRowDateText(row) + " · " + (row.note ?: row.transactionKind.name)
    val amount = formatMinorUnits(row.amountMinor, row.currency.precision) + " " + row.currency.code
    return "$head · $amount · ${reconciliationStatusText(row.status)}"
}

// ------------------------------------------------------------------ summary (spec section 2.3)

/**
 * One currency's summary line: 已核对合计 / 未核对合计 / 条数 (spec section 2.3, the ONLY permitted
 * aggregate phrasing — never 已对账, never a balance-minus-detail difference). 已核对 is exactly
 * [P408ReconciliationStatus.CHECKED]; every other status is 未核对. Each amount is the exact integer
 * minor-unit sum of its group, formatted with that currency's precision.
 */
internal fun reconciliationSummaryLine(
    currency: CurrencyUnit,
    totals: List<P801AccountReconciliationStatusTotal>,
): String {
    val ofCurrency = totals.filter { total -> total.currency == currency }
    val checked = ofCurrency.filter { total -> total.status == P408ReconciliationStatus.CHECKED }
    val unchecked = ofCurrency.filter { total -> total.status != P408ReconciliationStatus.CHECKED }
    val checkedSum = checked.sumOf { total -> total.totalMinorUnits }
    val uncheckedSum = unchecked.sumOf { total -> total.totalMinorUnits }
    val count = ofCurrency.sumOf { total -> total.rowCount.toLong() }
    return currency.code + " 已核对合计 " + formatMinorUnits(checkedSum, currency.precision) +
        " · 未核对合计 " + formatMinorUnits(uncheckedSum, currency.precision) +
        " · 条数 " + count
}

/**
 * The summary block, one line per currency the receipt carries (the read returns only groups the
 * ledger has, so an absent currency stays absent — D-089). Order is the read's own order (currency
 * code ascending), never a re-rank.
 */
internal fun reconciliationSummaryLines(totals: List<P801AccountReconciliationStatusTotal>): List<String> {
    val currencies = totals.map { total -> total.currency }.distinct()
    return currencies.map { currency -> reconciliationSummaryLine(currency, totals) }
}

// ------------------------------------------------------------------ candidates (spec section 2.4)

/**
 * The disposition line of one proposal (spec section 2.4). `AMBIGUOUS` asks for the explicit pick,
 * `PROPOSED_MATCH` names the single candidate, and `UNRESOLVED` names its OWN cause (F9): the three
 * matcher causes are different situations, so one 「无唯一候选」 for all three was a false claim.
 * The raw reason token still appears verbatim in [reconciliationUnresolvedCauseText].
 */
internal fun reconciliationDispositionText(
    disposition: P408MatchDisposition,
    reason: String? = null,
): String =
    when (disposition) {
        P408MatchDisposition.PROPOSED_MATCH -> "唯一候选"
        P408MatchDisposition.AMBIGUOUS -> "多个候选，请明确选择其中一条"
        P408MatchDisposition.UNRESOLVED ->
            when (reason) {
                "no_unique_funding_candidate" -> "没有唯一候选分录"
                "source_time_unresolved" -> "来源时间不可比，无法裁定候选"
                "funding_facts_unresolved" -> "资金事实不可解析，无法裁定候选"
                else -> "无法确定对应关系"
            }
    }

/**
 * The matcher's own cause, quoted verbatim (spec section 2.4: 原样转述 — the three causes are
 * `no_unique_funding_candidate`, `source_time_unresolved`, `funding_facts_unresolved`). A missing
 * reason renders the typed-absence dash, never an invented cause.
 */
internal fun reconciliationUnresolvedCauseText(reason: String?): String = "成因：" + (reason ?: "—")

/** The matcher's own natural-day distance of one candidate (spec section 2.4). */
internal fun reconciliationNaturalDayDistanceText(naturalDayDistance: Long): String = "自然日距离 $naturalDayDistance 天"

/**
 * The frozen five-field match basis, rendered as the persisted tokens in the canonical field order
 * of [com.unifiedledger.application.P408Matcher.MATCH_FIELDS]. No token is renamed, dropped or
 * added: an unexpected token (a stored basis written by an older contract) still renders, in
 * storage order, after the known five.
 */
internal fun reconciliationMatchBasisText(fields: List<String>): String {
    val known = com.unifiedledger.application.P408Matcher.MATCH_FIELDS
    val ordered = known.filter { token -> token in fields } + fields.filter { token -> token !in known }
    return ordered.joinToString(" · ")
}

/** One candidate's line: exact amount + currency, the leg's date and the natural-day distance. */
internal fun reconciliationCandidateText(candidate: com.unifiedledger.application.P408MatchCandidate): String {
    val posting = candidate.posting
    val amount = formatMinorUnits(posting.amountMinor, posting.currencyPrecision) + " " + posting.currencyCode
    val date = posting.occurredAt.rawText.take(10)
    return "$date · $amount · ${reconciliationNaturalDayDistanceText(candidate.basis.naturalDayDistance)}"
}

/**
 * One evidence row's line (spec section 2.4): which import row it is (来源时间) and its RAW amount
 * when the source carries one — an absent raw fact renders the typed dash, never a zero (D-089).
 */
internal fun reconciliationEvidenceText(evidence: P801ReconciliationCandidateEvidence): String {
    val time = evidence.occurredAt.rawText.ifBlank { "—" }
    val amountMinor = evidence.rawAmountMinor
    val precision = evidence.rawCurrencyPrecision
    val code = evidence.rawCurrencyCode
    val amount = if (amountMinor != null && precision != null && code != null) formatMinorUnits(amountMinor, precision) + " " + code else "—"
    return "来源时间 $time · $amount"
}

/** The proposal's evidence line (spec section 2.4: 每条各自给出处置). */
internal fun reconciliationProposalEvidenceText(proposal: P801CandidateProposal): String = reconciliationEvidenceText(proposal.evidence)

/** One bindable evidence row of the leg-anchored bind step (F1/F2): its line plus the REJECTED code. */
internal fun reconciliationBindableEvidenceText(evidence: P801ReconciliationCandidateEvidence): String {
    val rejection = evidence.projection?.rejectionCode?.let { code -> " · " + reconciliationFailureText(code) } ?: ""
    return reconciliationEvidenceText(evidence) + rejection
}

// ------------------------------------------------------------------ bind / confirm outcomes

/**
 * The detail screen's notice copy (spec sections 2.4/2.5). Every rejection keeps its raw code
 * token; the bind replay keeps 未重复写入 (spec section 2.4: 零新行); the result-unknown case uses
 * the spec's own sentence for both writes, because both are re-judged by the same authoritative
 * re-read on entry (i3a D2 — no request identity is carried across, so no receipt is ever invented).
 *
 * F10: the success copy follows the ACTUAL return target — the queue/totals only exist on the
 * entry A target, so the entry B target never claims they were refreshed.
 */
internal fun reconciliationNoticeText(notice: P503AppState.ReconciliationDetailNotice): String =
    when (notice) {
        is P503AppState.ReconciliationDetailNotice.Linked ->
            if (notice.queueRefreshed) {
                "已核对，队列、合计与审计已刷新。"
            } else {
                "已核对，审计已刷新。"
            }
        P503AppState.ReconciliationDetailNotice.NoChange -> "已核对（未重复写入）。"
        is P503AppState.ReconciliationDetailNotice.Rejected -> reconciliationFailureText(notice.code)
        is P503AppState.ReconciliationDetailNotice.Refused -> reconciliationRefusalText(notice.reason, notice.rejectionCode)
        P503AppState.ReconciliationDetailNotice.UnknownResult -> RECONCILIATION_UNKNOWN_RESULT_TEXT
        // F7: the owner's readiness gate refused BEFORE the call ran, so nothing was written.
        P503AppState.ReconciliationDetailNotice.RuntimeNotReady -> "账本尚未就绪，本次未写入，请稍后重试。"
        P503AppState.ReconciliationDetailNotice.Bound -> "已绑定，该导入行现在可用于核对。"
        P503AppState.ReconciliationDetailNotice.BindNoChange -> "绑定已存在，未重复写入。"
        is P503AppState.ReconciliationDetailNotice.BindRejected -> reconciliationFailureText(notice.code)
        is P503AppState.ReconciliationDetailNotice.BindRefused -> reconciliationBindRefusalText(notice.reason)
        P503AppState.ReconciliationDetailNotice.BindUnknownResult -> RECONCILIATION_UNKNOWN_RESULT_TEXT
    }

/** One rejection, readable label + the raw infrastructure code token (spec section 2.5). */
internal fun reconciliationFailureText(code: String): String = reconciliationFailureLabel(code) + "（" + code + "）"

/**
 * The readable label of a rejection code. The codes are the frozen infrastructure vocabulary of the
 * two existing write ports; an unknown code keeps the generic label so the token (always appended by
 * [reconciliationFailureText]) is what the reader can act on — no code is ever mapped to a silent
 * success.
 */
internal fun reconciliationFailureLabel(code: String): String =
    when (code) {
        "P408_EVIDENCE_ALREADY_LINKED" -> "该导入行已用于核对"
        "P408_POSTING_RESPONSIBILITY_ALREADY_LINKED" -> "该分录的此职责已核对"
        "P408_POSTING_NOT_ELIGIBLE" -> RECONCILIATION_NOT_CONFIRMABLE_TEXT
        "P408_POSTING_FACT_MISMATCH" -> "分录事实与请求不一致"
        "P408_POSTING_TIME_UNRESOLVED" -> "分录时间不可解析"
        "P408_POSTING_TIME_WINDOW_MISMATCH" -> "来源时间超出允许的匹配窗口"
        "P408_EVIDENCE_NOT_FOUND" -> "导入行不存在或不在当前账本"
        "P408_RESPONSIBILITY_POSTING_MISMATCH" -> "证据职责与分录方向不一致"
        "P408_REQUEST_IDENTITY_CONFLICT" -> "请求身份冲突"
        "P408_REQUEST_BASIS_VERSION_RETIRED" -> "请求依据版本已停用"
        "P408_WINDOW_DAYS_NOT_APPROVED" -> "匹配窗口未获批准"
        "P408_TRANSACTION_ID_MISMATCH" -> "交易身份与分录不一致"
        "P408_RECONCILIATION_ID_MISMATCH" -> "核对身份与分录不一致"
        "P408_RECONCILIATION_MISSING" -> "该分录缺少对账事实"
        "P408_SOURCE_FACT_UNRESOLVED" -> "导入行来源事实不可解析"
        "P408_PROJECTION_ABSENT" -> "导入行尚未绑定账户"
        "P408_PROJECTION_NOT_READY" -> "导入行绑定尚未就绪"
        "P408_PROJECTION_STATE_CONFLICT" -> "导入行绑定状态冲突"
        "P408_PROJECTION_SOURCE_DRIFT" -> "导入行来源已变更"
        "P408_PROJECTION_CURRENCY_MISMATCH" -> "导入行与目标账户币种不一致"
        "P408_PROJECTION_AMOUNT_NOT_REPRESENTABLE" -> "金额无法按目标账户币种精确表示"
        "P408_PROJECTION_ARITHMETIC_OVERFLOW" -> "金额换算溢出"
        "P408_RECONCILIATION_CONSTRAINT_VIOLATION" -> "写入被约束拒绝，未落任何行"
        "P408_RECEIPT_NOT_FOUND" -> "回执缺失"
        "P408_REQUEST_NOT_FOUND" -> "请求记录缺失"
        else -> "核对请求被拒绝"
    }

/**
 * A REJECTED projection's own copy (spec section 2.4 / F2): the stored rejection code is rendered
 * verbatim beside the existing code label, so the screen never reduces every rejection to the same
 * generic 「绑定被拒绝」. A rejected row always carries a code (the schema's CHECK); the code-less
 * shape is only the typed absence of a projection altogether.
 */
internal fun reconciliationProjectionRejectionText(projection: com.unifiedledger.application.P408EvidenceProjection?): String =
    projection?.rejectionCode?.let { code -> "该导入行的绑定被拒绝：" + reconciliationFailureText(code) }
        ?: "该导入行的绑定被拒绝。"

/**
 * The pre-write refusal copy of the confirm use case (spec section 2.5; each writes nothing).
 * [rejectionCode] is the REJECTED projection's own code when the row carried one (F2).
 */
internal fun reconciliationRefusalText(
    reason: P801ConfirmLinkRefusal,
    rejectionCode: String? = null,
): String =
    when (reason) {
        P801ConfirmLinkRefusal.CANDIDATE_ID_UNAVAILABLE -> "该导入行没有候选身份，无法确认。"
        P801ConfirmLinkRefusal.SOURCE_TIME_UNRESOLVED -> "导入行的来源时间不可解析，无法确认。"
        P801ConfirmLinkRefusal.EVIDENCE_NOT_PROJECTED -> "该导入行尚未绑定账户，需先绑定。"
        P801ConfirmLinkRefusal.EVIDENCE_PROJECTION_REJECTED ->
            rejectionCode?.let { code -> "该导入行的绑定被拒绝，无法确认：" + reconciliationFailureText(code) }
                ?: "该导入行的绑定被拒绝，无法确认。"
        P801ConfirmLinkRefusal.RESPONSIBILITY_UNRESOLVED -> "无法按该分录方向裁定证据职责。"
    }

/** The pre-write refusal copy of the bind use case (spec section 2.4; each writes nothing). */
internal fun reconciliationBindRefusalText(reason: P801BindEvidenceRefusal): String =
    when (reason) {
        P801BindEvidenceRefusal.EVIDENCE_NOT_FOUND -> "导入行不存在或不在当前账本。"
        P801BindEvidenceRefusal.EVIDENCE_READ_UNAVAILABLE -> "导入行读取失败。"
        P801BindEvidenceRefusal.PROJECTION_TARGETS_ANOTHER_ACCOUNT -> "该导入行已绑定到其他账户，应用内不能改绑。"
        P801BindEvidenceRefusal.SOURCE_FACTS_UNRESOLVED -> "导入行的来源事实不完整，无法绑定。"
        P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_IN_CATALOG -> "目标账户不在目录中。"
        P801BindEvidenceRefusal.TARGET_ACCOUNT_NOT_RECONCILABLE -> "目标账户不支持对账。"
    }

/**
 * Why the proposal read produced no disposition list at all (spec sections 2.1/2.4). The
 * non-confirmable leg case reuses the spec's own sentence instead of a generic failure; a REJECTED
 * projection appends its stored code (F2) through [reconciliationProjectionRejectionText].
 */
internal fun reconciliationProposalFailureText(
    failure: P801CandidateProposalFailure,
    projection: com.unifiedledger.application.P408EvidenceProjection? = null,
): String =
    when (failure) {
        P801CandidateProposalFailure.CANDIDATE_EVIDENCE_UNAVAILABLE -> "导入行读取失败。"
        P801CandidateProposalFailure.POSTING_FACTS_UNAVAILABLE -> "分录事实读取失败。"
        P801CandidateProposalFailure.LEG_NOT_IN_QUEUE -> "该分录不在该账户的对账队列中。"
        P801CandidateProposalFailure.LEG_NOT_TRANSFER_CONFIRMABLE -> RECONCILIATION_NOT_CONFIRMABLE_TEXT
        P801CandidateProposalFailure.EVIDENCE_NOT_FOUND -> "导入行不存在或不在当前账本。"
        P801CandidateProposalFailure.EVIDENCE_NOT_PROJECTED -> "该导入行尚未绑定账户，需先绑定。"
        P801CandidateProposalFailure.EVIDENCE_PROJECTION_REJECTED -> reconciliationProjectionRejectionText(projection)
        P801CandidateProposalFailure.EVIDENCE_FACTS_UNRESOLVED -> "导入行的来源事实不完整。"
        P801CandidateProposalFailure.SOURCE_TIME_UNRESOLVED -> "导入行的来源时间不可解析。"
    }

/** The empty candidate list (a successful read that found nothing in the window — not a failure). */
internal fun reconciliationNoCandidateEvidenceText(): String = "该窗口内没有可用于核对的导入行。"

/**
 * The entry A bind step's empty copy (spec section 2.4 / F1c): an empty bind list means NO evidence
 * is waiting to be bound for THIS account/window — never the (different) claim that the window has
 * no import rows at all, which [reconciliationNoCandidateEvidenceText] would make.
 */
internal fun reconciliationNoBindableEvidenceText(): String = "该账户在该窗口内没有等待绑定的导入证据。"

/** The bind list read's failure copy (never presented as the empty bind answer, D-089). */
internal fun reconciliationBindableEvidenceFailureText(): String = "待绑定证据读取失败。"

// ------------------------------------------------------------------ audit read-back (spec 2.6)

/** The evidence source line: 哪一次导入、哪条来源行 (spec section 2.6). */
internal fun reconciliationAuditSourceText(link: P801ReconciledLegEvidenceLink): String {
    val source = link.source
    val ordinal = "第 ${source.recordOrdinal} 条"
    val time = source.occurredAt ?: "—"
    return "证据来源：导入 ${source.importInputRef} · $ordinal（${source.recordKind}）· 来源时间 $time"
}

/** The evidence responsibility line (spec section 2.6). */
internal fun reconciliationAuditResponsibilityText(link: P801ReconciledLegEvidenceLink): String = "$RECONCILIATION_ELEMENT_RESPONSIBILITY：${reconciliationResponsibilityText(link.responsibility)}"

/** The decision line: the four elements plus the basis version/window and the decision time (spec sections 2.5/2.6). */
internal fun reconciliationAuditDecisionLines(link: P801ReconciledLegEvidenceLink): List<String> {
    val decision = link.decision ?: return listOf("$RECONCILIATION_ELEMENT_MATCH_BASIS：" + "—（无请求快照）")
    return listOf(
        "$RECONCILIATION_ELEMENT_EXTERNAL_EVIDENCE：${link.evidenceId}",
        reconciliationAuditResponsibilityText(link),
        "$RECONCILIATION_ELEMENT_MATCH_BASIS：${reconciliationMatchBasisText(decision.matchBasis)}",
        // F12d: the persisted basis version and window are rendered (not kept as unread fields).
        "匹配依据版本 v${decision.basisVersion} · 窗口 ${decision.windowDays} 天",
        reconciliationNaturalDayDistanceText(decision.naturalDayDistance.toLong()),
        "$RECONCILIATION_ELEMENT_HUMAN_DECISION：${decision.humanDecision} · ${decision.decidedAt}",
    )
}

/** The link's own state history (spec section 2.6: confirmed/posting_replaced/corrected 与序号). */
internal fun reconciliationAuditLinkHistoryText(entry: P801ReconciledLegLinkHistoryEntry): String = "#${entry.sequence} ${entry.state} ${entry.reason} ${entry.occurredAt}"

/** The leg's reconciliation status history (spec section 2.6 状态历史 with sequence). */
internal fun reconciliationAuditStatusHistoryText(entry: P801ReconciledLegStatusEntry): String = "#${entry.sequence} ${reconciliationStatusText(entry.status)} ${entry.occurredAt}"

/**
 * The audit's overall headline: the leg and its current reconciliation status (F5). A leg with NO
 * `posting_reconciliation` row is merely unrecorded — it may well be `PENDING` in the queue — so it
 * renders [RECONCILIATION_NO_RECONCILIATION_TEXT], never the ineligibility copy. The
 * ineligibility copy stays reserved for a leg the eligibility derivation actually excluded.
 */
internal fun reconciliationAuditHeadlineText(audit: P801ReconciledLegAudit): String = "分录 ${audit.postingId.value} · " + (audit.reconciliation?.status?.label ?: RECONCILIATION_NO_RECONCILIATION_TEXT)

/** The audit's empty status-history copy (a reconciliation row exists but carries no history). */
internal fun reconciliationAuditEmptyHistoryText(): String = RECONCILIATION_NO_STATUS_HISTORY_TEXT

/** The audit's empty-link copy (an unchecked leg: a typed absence, never a zero-filled audit). */
internal fun reconciliationAuditNoLinkText(): String = "该分录尚未核对（无活动证据链接）。"

/** The audit read's failure copy (never an empty audit standing in for a failed read). */
internal fun reconciliationAuditFailureText(): String = "审计读取失败。"

/** The audit's absent-leg verdict (the posting id names no leg on this ledger). */
internal fun reconciliationAuditNotFoundText(): String = "该分录不存在或不在当前账本。"

/** Whether a link is currently active (its latest history state is `active`). */
internal fun reconciliationLinkIsActive(link: P801ReconciledLegEvidenceLink): Boolean = link.state == "active"

/**
 * Whether the matcher candidate may be confirmed (spec sections 2.4/2.5): the leg must be
 * confirmable AND no ACTIVE link of the SAME responsibility may already exist (the existing
 * `P408_POSTING_RESPONSIBILITY_ALREADY_LINKED` refusal, presented rather than pre-empted).
 *
 * The audit read belongs to the ANCHOR leg only (F11), so a candidate on a SIBLING leg is judged
 * neutrally: its link state is unknown here, and claiming "already linked" from another leg's
 * audit would be a false per-candidate claim.
 */
internal fun reconciliationCandidateConfirmable(
    candidate: com.unifiedledger.application.P408MatchCandidate,
    audit: P801ReconciledLegAudit?,
    anchorPostingId: com.unifiedledger.domain.PostingId?,
): Boolean {
    if (anchorPostingId == null || candidate.posting.postingId != anchorPostingId.value) return true
    // An audit that belongs to a DIFFERENT posting (e.g. a sibling leg confirmed a moment ago) says
    // nothing about the anchor leg; treating it as the anchor's would falsely block the anchor.
    if (audit != null && audit.postingId != anchorPostingId) return true
    val responsibility = P408EvidenceResponsibility.forDirectionOrNull(candidate.posting.direction) ?: return false
    val existing = audit?.evidence.orEmpty().filter { link -> reconciliationLinkIsActive(link) }
    return existing.none { link -> link.responsibility == responsibility }
}

/**
 * The reason a confirmable candidate is unavailable, or null when it is confirmable (spec 2.4).
 * F9: a leg whose direction decides no duty gets its OWN sentence — the same-responsibility
 * sentence would describe a conflict that was never established.
 */
internal fun reconciliationCandidateBlockReason(
    candidate: com.unifiedledger.application.P408MatchCandidate,
    audit: P801ReconciledLegAudit?,
    anchorPostingId: com.unifiedledger.domain.PostingId?,
): String? =
    when {
        reconciliationCandidateConfirmable(candidate, audit, anchorPostingId) -> null
        P408EvidenceResponsibility.forDirectionOrNull(candidate.posting.direction) == null -> "无法按该分录方向裁定证据职责"
        else -> "该分录的此职责已核对，其余证据不可再确认"
    }

// ------------------------------------------------------------------ selection / step decisions

/**
 * The pair a selection addresses, or `null` when the indices do not name an offered candidate.
 * THE one selection-range check (F13): the reducer's stale-pick guard, the four-element block and
 * the confirm gating all read it, so a pick can never render in one place and be refused in another.
 */
internal fun reconciliationPairAt(
    proposals: com.unifiedledger.application.P801CandidateProposalResult?,
    selection: P503AppState.ReconciliationSelection?,
): Pair<P801CandidateProposal, com.unifiedledger.application.P408MatchCandidate>? {
    if (selection == null) return null
    val list = (proposals as? com.unifiedledger.application.P801CandidateProposalResult.Success)?.proposals ?: return null
    val proposal = list.getOrNull(selection.proposalIndex) ?: return null
    val candidate = proposal.match.candidates.getOrNull(selection.candidateIndex) ?: return null
    return proposal to candidate
}

/**
 * The selected proposal and its chosen matcher candidate, or `null` when nothing validable is
 * selected. Both the four-element block and the confirm gating read this ONE resolver, so the
 * screen can never show one candidate's basis while confirming another.
 */
internal fun reconciliationSelectedPair(
    state: P503AppState.ReconciliationDetail,
): Pair<P801CandidateProposal, com.unifiedledger.application.P408MatchCandidate>? = reconciliationPairAt(state.proposals, state.selectedCandidate)

/**
 * Whether the bind step renders (spec section 2.4; F1/F11).
 *
 * - Entry A (a leg anchor) always renders it: the leg's account/window is known and the host read
 *   the bindable evidence list, so the user can reach 账户 → 队列 → 绑定 (V-10). An empty list has
 *   its own copy — never 「no import rows in this window」.
 * - Entry B renders it only while the ANCHOR EVIDENCE has no `READY` projection (unprojected, or
 *   REJECTED with its code). The evidence row's projection state decides, NOT whether the proposal
 *   read succeeded: a failed posting-facts read on an already-projected evidence must not offer a
 *   bind, and a null evidence row (a failed evidence read) must not either.
 */
internal fun reconciliationBindStepVisible(state: P503AppState.ReconciliationDetail): Boolean =
    when {
        state.anchorPostingId != null -> true
        state.anchorEvidenceId != null ->
            state.anchorEvidence?.let { row -> row.projection?.state != com.unifiedledger.application.P408ProjectionState.READY } == true
        else -> false
    }

/** Whether the confirm action is currently available (the four elements are on screen and no write runs). */
internal fun reconciliationConfirmEnabled(state: P503AppState.ReconciliationDetail): Boolean {
    val (_, candidate) = reconciliationSelectedPair(state) ?: return false
    if (state.binding || state.confirming) return false
    val audit = (state.audit as? com.unifiedledger.application.P801ReconciledLegAuditResult.Success)?.audit
    return reconciliationCandidateConfirmable(candidate, audit, state.anchorPostingId)
}

/**
 * Whether the bind action is currently available: a declared target, a chosen evidence row, and no
 * write in flight. Entry A fixes the target to the leg's account and takes the evidence from the
 * bindable list; entry B takes both from the anchor.
 */
internal fun reconciliationBindEnabled(state: P503AppState.ReconciliationDetail): Boolean = !state.binding && !state.confirming && state.bindTarget != null && state.bindEvidenceId != null

/**
 * The footer state of the queue (F12b): the SAME frozen [FlowFooterState] vocabulary the account
 * flow uses, so the two keyset-paged lists share one footer implementation.
 */
internal fun reconciliationQueueFooterState(state: P503AppState.ReconciliationQueue): FlowFooterState =
    when {
        state.rowsLoading -> FlowFooterState.LOADING
        state.rowsLoadFailed -> FlowFooterState.RETRY
        state.nextCursor != null -> FlowFooterState.FETCH_MORE
        else -> FlowFooterState.END
    }
