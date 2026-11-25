package com.unifiedledger.android

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsPageResult
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationReadPort
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801CandidateEvidenceResult
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.application.P801ReconciliationCandidateEvidence
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.ui.LeaseOutcome
import com.unifiedledger.ui.P503LedgerFacade
import kotlin.math.abs

/**
 * P8-07 Q2 flow 2 device evidence: IMPORT EVIDENCE → RECONCILIATION → AUDIT RE-READ, on the dev
 * device, over the shared anonymous starting seed that [P807Q2Flow1Seed] builds.
 *
 * WHAT THIS FILE IS. The flow's own pieces, and only those: the copy table this flow asserts
 * against, the test-side reconciliation read accessor, and the reconciliation/import-review UI
 * steps that [P807Q2Flow1Ui] does not already own. The seed, the formal-ledger snapshot, the
 * generic launch/seek/click discipline and the "no open ledger handle" guard are reused from the
 * frozen flow-1 support file exactly as they are; nothing here re-implements them.
 *
 * THE READ ACCESSOR IS TEST-SIDE, NOT A PRODUCT SEAM. [P807Q2Flow2Reconciliation] reaches the
 * LAUNCHED app's one runtime owner through `ledgerProcessHolderFor(context).ledger` (the shape
 * `P807Q2Flow1Ledger` already uses for the formal reads, `P807Q2Flow1DeviceSupport.kt:743-758`),
 * takes an operation lease (`LedgerLeaseScope.leased`) and calls the composition-root-bound
 * `P801AccountReconciliationReadPort` (`LedgerRuntimeOwner.kt:747`) inside it. Every assertion it
 * feeds is therefore an authoritative product read, taken under the same lease a product screen
 * would hold — it adds no read path to the product.
 *
 * COPY DISCIPLINE. The strings in [P807Q2Flow2Facts] are `internal` to `app-ui`, which androidTest
 * cannot see, so each one is mirrored verbatim with the `app-ui` line it comes from (the P805/P807
 * mirror discipline). A product copy change makes the flow's assertion fail loudly instead of
 * passing against a stale mirror; the device-evidence report carries the table.
 *
 * WHAT THIS FILE DOES NOT DO. It writes nothing to the ledger, and it never judges a product rule
 * itself: the UI copy and the port payloads are compared, and the formal-ledger snapshot equality
 * is the zero-accounting-effect oracle.
 *
 * The flow-2 copy table, seed facts and helpers shared by the flow's own device case.
 *
 * Every UI literal carries the `app-ui` (or `ledger-application`) line that produces it, so a
 * divergence is traceable from the assertion to the product source instead of to a memory of it.
 */
internal object P807Q2Flow2Facts {
    /** The one grep-able device-evidence prefix of this flow. */
    const val PREFIX = "P807-Q2FLOW2"

    // ---------------------------------------------------------------- product copy (app-ui mirrors)

    /** `RECONCILIATION_TITLE` — `P503ReconciliationPresentation.kt:39`. */
    const val RECONCILIATION_TITLE = "账户对账"

    /** `RECONCILIATION_EVIDENCE_ENTRY_TITLE` — `P503ReconciliationPresentation.kt:42`. */
    const val EVIDENCE_ENTRY_TITLE = "用于核对既有分录"

    /** `IMPORT_USED_FOR_RECONCILIATION_TEXT` — `P503ReconciliationPresentation.kt:48`. */
    const val USED_FOR_RECONCILIATION = "已用于核对既有分录"

    /** `RECONCILIATION_BIND_IRREVERSIBLE_WARNING` — `P503ReconciliationPresentation.kt:51`. */
    const val BIND_IRREVERSIBLE_WARNING = "绑定后不能在应用内改绑"

    /** The bind section's title — `P503ReconciliationScreen.kt:352`. */
    const val BIND_HEADER = "绑定到账户"

    /** The bind button — `P503ReconciliationScreen.kt:403-405`. */
    const val BIND_BUTTON = "绑定"

    /** The bind success notice — `P503ReconciliationPresentation.kt:310`. */
    const val BOUND_NOTICE = "已绑定，该导入行现在可用于核对。"

    /** `RECONCILIATION_CANDIDATES_HEADER` — `P503ReconciliationPresentation.kt:90`. */
    const val CANDIDATES_HEADER = "候选证据"

    /** `PROPOSED_MATCH`'s disposition line — `P503ReconciliationPresentation.kt:222`. */
    const val PROPOSED_MATCH = "唯一候选"

    /** `RECONCILIATION_ELEMENT_EXTERNAL_EVIDENCE` — `P503ReconciliationPresentation.kt:77`. */
    const val ELEMENT_EXTERNAL_EVIDENCE = "外部证据"

    /** `RECONCILIATION_ELEMENT_RESPONSIBILITY` — `P503ReconciliationPresentation.kt:78`. */
    const val ELEMENT_RESPONSIBILITY = "证据职责"

    /** `RECONCILIATION_ELEMENT_MATCH_BASIS` — `P503ReconciliationPresentation.kt:79`. */
    const val ELEMENT_MATCH_BASIS = "匹配依据"

    /** `RECONCILIATION_ELEMENT_HUMAN_DECISION` — `P503ReconciliationPresentation.kt:80`. */
    const val ELEMENT_HUMAN_DECISION = "人工决定"

    /** The natural-day distance of the seeded pair — `P503ReconciliationPresentation.kt:241`. */
    const val NATURAL_DAY_DISTANCE = "自然日距离 0 天"

    /**
     * The `in` leg's evidence duty, as the surface prints it: `reconciliationResponsibilityText`
     * renders the PERSISTED token (`P503ReconciliationPresentation.kt:158`) and the direction rule
     * is `P408EvidenceResponsibility.forDirectionOrNull` (`P408Reconciliation.kt:22-27`).
     */
    const val RESPONSIBILITY_DESTINATION = "destination_asset_posting"

    /** The persisted human-decision token — `P503ReconciliationPresentation.kt:87`. */
    const val HUMAN_DECISION_CONFIRM_MATCH = "confirm_match"

    /** The confirm action — `P503ReconciliationScreen.kt:490-492`. */
    const val CONFIRM_BUTTON = "确认核对"

    /**
     * The entry-B confirm notice. The queue totals only exist on the entry-A return target, so the
     * evidence-focused return says exactly this (`P503ReconciliationPresentation.kt:296-303`).
     */
    const val CONFIRMED_NOTICE_EVIDENCE_TARGET = "已核对，审计已刷新。"

    /** `RECONCILIATION_AUDIT_HEADER` — `P503ReconciliationPresentation.kt:91`; `:291`. */
    const val AUDIT_HEADER = "审计回看"

    /** `RECONCILIATION_HISTORY_HEADER` — `P503ReconciliationPresentation.kt:92`; `:331`. */
    const val HISTORY_HEADER = "状态历史"

    /** The unchecked partition header, count 1 — `P503ReconciliationPresentation.kt:130`; `:136`. */
    const val UNCHECKED_ONE = "未核对 1 条"

    /** The unchecked partition header, count 0 — `P503ReconciliationPresentation.kt:130`. */
    const val UNCHECKED_ZERO = "未核对 0 条"

    /** The collapsed checked partition with one leg — `P503ReconciliationPresentation.kt:136-139`. */
    const val CHECKED_ONE_COLLAPSED = "已核对 1 条 · 展开"

    /**
     * The same partition's stable prefix, used where the partition may already be expanded
     * (`已核对 1 条 · 收起`): the queue's carried state keeps the expansion across a leg-detail round
     * trip, so a return trip must not insist on the collapsed wording.
     */
    const val CHECKED_ONE_PREFIX = "已核对 1 条 · "

    /**
     * The whole-account totals BEFORE the confirm: one pending leg of 12.34 CNY, nothing checked
     * (`P503ReconciliationPresentation.kt:184-197`; groups are `(currency, status)`).
     */
    const val SUMMARY_BEFORE = "CNY 已核对合计 0.00 · 未核对合计 12.34 · 条数 1"

    /** The same totals AFTER the confirm: the one leg is checked (`P503ReconciliationPresentation.kt:184-197`). */
    const val SUMMARY_AFTER = "CNY 已核对合计 12.34 · 未核对合计 0.00 · 条数 1"

    /**
     * The queue row before the confirm: date · note · exact signed amount and currency ·
     * five-state label (`P503ReconciliationPresentation.kt:163-174`; the label is
     * `P408ReconciliationStatus.PENDING.label`, `P408Reconciliation.kt:36`).
     */
    const val QUEUE_ROW_PENDING = "2026-10-14 · P807 对账样本转账 · 12.34 CNY · 待对账"

    /** The same row after the confirm (`P408ReconciliationStatus.CHECKED.label`, `P408Reconciliation.kt:40`). */
    const val QUEUE_ROW_CHECKED = "2026-10-14 · P807 对账样本转账 · 12.34 CNY · 已核对"

    /**
     * The candidate line of the one proposed candidate: the leg's date and exact amount plus the
     * matcher's natural-day distance (`P503ReconciliationPresentation.kt:256-261`).
     */
    const val CANDIDATE_LINE = "2026-10-14 · 12.34 CNY · 自然日距离 0 天"

    // ---------------------------------------------------------------- navigation copy

    /** The dock tabs (`P503TabShell.kt:111-113`). */
    const val HOME_TAB = "首页"
    const val ACCOUNT_TAB = "账户"

    /** The accounts region's grouped heading prefix (`P503AssetsScreen.kt:260`). */
    const val ACCOUNTS_GROUP_PREFIX = "资产 · "

    /** The account-flow surface's own title (`P503AccountFlowScreen.kt:92`). */
    const val ACCOUNT_FLOW_TITLE = "账户流水"

    /** The drawer's import row (`P503TabShell.kt:395`); the drawer itself opens from 打开菜单 (`:241`). */
    const val DRAWER_IMPORT_ROW = "导入账单"

    /** The import review list's pending section (`P503ImportReview.kt:623`). */
    const val REVIEW_PENDING_HEADER = "待确认草稿"

    /**
     * The seeded candidate's amount line in the review list, produced by
     * `importCandidateAmountText` (`P503ImportReviewPresentation.kt:263-272`) over the seed's
     * 1_234 minor units at CNY's 2-digit precision.
     */
    const val REVIEW_CANDIDATE_AMOUNT = "12.34 CNY"

    /** The candidate detail's title (`P503ImportReview.kt:925`). */
    const val CANDIDATE_DETAIL_TITLE = "候选详情"

    /** The decision form's section labels (`P503ImportReview.kt:1152,1160`). */
    const val FROM_ACCOUNT_SECTION = "转出账户"
    const val TO_ACCOUNT_SECTION = "转入账户"

    /** The decision-complete marker (`P503ImportReview.kt:1024`). */
    const val DECISION_COMPLETE = "决策已补全。"

    /** The detail-only selection hint (`P503ImportReview.kt:1071`) and the checkbox description (`:1069`). */
    const val SELECTION_HINT = "勾选进入批量确认（须先补齐决策）"
    const val CHECKBOX_DESCRIPTION = "勾选候选"

    /** The batch entries: the detail's (`P503ImportReview.kt:1045`) and the authorization page's (`P503ImportBatch.kt:244,277`). */
    const val BATCH_ENTRY = "进入批量确认"
    const val BATCH_TITLE = "批量确认"
    const val BATCH_AUTHORIZE_ONE = "确认入账（1 项）"

    /** The shared back label (`P503ReconciliationScreen.kt:234`; `P503ImportBatch.kt:245`). */
    const val BACK_LABEL = "返回"

    /**
     * The retained batch result's summary prefix and the two facts the double-booking refusal must
     * produce (`P503ImportBatchPresentation.kt:609,616`).
     */
    const val BATCH_RESULT_PREFIX = "最近批量结果（授权时间 "
    const val BATCH_ZERO_CONFIRMED_ONE_REJECTED = "已入账 0 项，拒绝 1 项"

    /** The import confirm use case's double-booking refusal token (`ImportSpine.kt:440`). */
    const val IMPORT_EVIDENCE_ALREADY_RECONCILED = "IMPORT_EVIDENCE_ALREADY_RECONCILED"

    /**
     * The 转出/转入 decision the batch admission needs before it can submit the seeded
     * `transfer_flow` candidate (`P503ImportBatchPresentation.kt:220-225,346-352`; the seed's
     * transfer moved 12.34 CNY from the default payment account into the reconcile account,
     * `P807Q2Flow1DeviceSupport.kt:587-628`). The default account's display name is the product's
     * own catalog seed, not a UI literal.
     */
    const val DECISION_FROM_ACCOUNT_NAME = "默认支付账户"

    /** The evidence the flow binds and confirms: the seed's unprojected import evidence. */
    val SEEDED_EVIDENCE_ID: String = P807Q2Flow1Facts.SEEDED_EVIDENCE_ID

    /** The seed's pending import candidate: the one the double-booking guard must refuse. */
    val SEEDED_CANDIDATE_ID: String = P807Q2Flow1Facts.SEEDED_CANDIDATE_ID

    /** The destination leg's posting id — the anchor the audit is read for. */
    val TRANSFER_DESTINATION_POSTING_ID: String = P807Q2Flow1Facts.TRANSFER_DESTINATION_POSTING_ID

    /**
     * The audit's evidence-source line for the seeded link
     * (`reconciliationAuditSourceText`, `P503ReconciliationPresentation.kt:432-437`): the import
     * pick-session handle, the record ordinal, the persisted source kind token
     * (`ImportRecordKind.TRANSFER_FLOW_SOURCE`, `ImportSpine.kt:73`) and the persisted source time.
     */
    const val AUDIT_SOURCE_LINE =
        "证据来源：导入 p807-shared-seed-statement · 第 0 条（transfer_flow_source）· 来源时间 2026-10-14T04:00:00Z"

    /** The evidence id the four-element block and the audit name (`P503ReconciliationScreen.kt:468`). */
    const val AUDIT_EXTERNAL_EVIDENCE_LINE = "外部证据：p807-seed-import-evidence"

    /** The four-element duty line (`P503ReconciliationScreen.kt:472-475`). */
    const val FOUR_ELEMENT_RESPONSIBILITY_LINE = ELEMENT_RESPONSIBILITY + "：" + RESPONSIBILITY_DESTINATION

    /** The four-element human-decision line (`P503ReconciliationScreen.kt:484-488`). */
    const val FOUR_ELEMENT_HUMAN_DECISION_LINE = ELEMENT_HUMAN_DECISION + "：" + HUMAN_DECISION_CONFIRM_MATCH

    /**
     * The order `reconciliationMatchBasisText` prints the frozen fields in
     * (`P503ReconciliationPresentation.kt:249-253` over `P408Matcher.kt:311-318`). The mirror is
     * needed only to compare the UI line against the PERSISTED basis the port returns.
     */
    val MATCH_FIELD_ORDER: List<String> = listOf("amount", "currency", "direction", "account", "occurred_at_window")

    /** The frozen five-field basis line this flow expects on both the four-element block and the audit. */
    const val MATCH_BASIS_LINE = ELEMENT_MATCH_BASIS + "：amount · currency · direction · account · occurred_at_window"

    /** The audit's headline for the confirmed destination leg (`P503ReconciliationPresentation.kt:468`). */
    val AUDIT_HEADLINE: String = "分录 " + TRANSFER_DESTINATION_POSTING_ID + " · 已核对"

    /** The per-item refusal line for the seeded candidate (`P503ImportBatchPresentation.kt:616`). */
    val BATCH_REJECTED_LINE: String =
        "候选 " + SEEDED_CANDIDATE_ID + "：拒绝（诊断码 " + IMPORT_EVIDENCE_ALREADY_RECONCILED + "），未写入。"

    /**
     * The audit's basis line for a persisted field list, mirroring
     * `reconciliationMatchBasisText`: the known five in [MATCH_FIELD_ORDER], then any unexpected
     * token in its stored position.
     */
    fun auditBasisLine(fields: List<String>): String {
        val ordered = MATCH_FIELD_ORDER.filter { token -> token in fields } + fields.filter { token -> token !in MATCH_FIELD_ORDER }
        return ELEMENT_MATCH_BASIS + "：" + ordered.joinToString(" · ")
    }

    /** The audit's duty line for a persisted responsibility (mirrors `reconciliationAuditResponsibilityText`). */
    fun auditResponsibilityLine(responsibility: P408EvidenceResponsibility): String = ELEMENT_RESPONSIBILITY + "：" + responsibility.storageValue

    /** The audit's human-decision line (mirrors the human-decision element of `reconciliationAuditDecisionLines`). */
    fun auditHumanDecisionLine(
        humanDecision: String,
        decidedAt: String,
    ): String = ELEMENT_HUMAN_DECISION + "：" + humanDecision + " · " + decidedAt

    /** The audit's source line for a read link (mirrors `reconciliationAuditSourceText`). */
    fun auditSourceLine(
        importInputRef: String,
        recordOrdinal: Long,
        recordKind: String,
        occurredAt: String?,
    ): String = "证据来源：导入 " + importInputRef + " · 第 " + recordOrdinal + " 条（" + recordKind + "）· 来源时间 " + (occurredAt ?: "—")
}

/**
 * One read of the launched ledger's formal face PLUS the version face: the flow-1 snapshot (which
 * already compares transactions, legs, balances and candidates as strings) plus every current
 * transaction's version id, so "版本不变" is observed rather than assumed.
 */
internal data class P807Q2Flow2Snapshot(
    val formal: P807FormalSnapshot,
    val versions: List<String>,
) {
    fun logLine(): String = formal.logLine() + " versions=[" + versions.joinToString(";") + "]"
}

/** Reads a [P807Q2Flow2Snapshot] through [facade]'s production read use cases (one current-state read). */
internal fun p807Q2Flow2ReadSnapshot(facade: P503LedgerFacade): P807Q2Flow2Snapshot {
    val state =
        when (val read = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> read.state
            LedgerCurrentStateResult.InvalidState -> error("the flow's ledger read as InvalidState")
            LedgerCurrentStateResult.Unavailable -> error("the flow's ledger read as Unavailable")
        }
    val versions =
        state.transactions
            .sortedBy { row -> row.transactionId.value }
            .map { row -> row.transactionId.value + "|" + row.currentVersionId.value }
    return P807Q2Flow2Snapshot(formal = p807Q2Flow1ReadSnapshot(facade), versions = versions)
}

/**
 * The flow's test-side reconciliation read accessor: the launched app's ONE runtime owner, its
 * operation lease, and the composition-root-bound [P801AccountReconciliationReadPort].
 *
 * A not-Ready runtime or a missing port is a hard failure: this flow's every judgement depends on
 * an authoritative read, so a degraded graph must stop the case instead of silently passing.
 */
internal class P807Q2Flow2Reconciliation(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val tag = P807Q2Flow2Facts.PREFIX

    /** The formal + version face of the launched ledger, through the flow-1 accessor's own lease. */
    fun snapshot(): P807Q2Flow2Snapshot = P807Q2Flow1Ledger(context).read(::p807Q2Flow2ReadSnapshot)

    /** One read through the reconciliation port, under an operation lease. */
    fun <T> read(block: (P801AccountReconciliationReadPort, P503LedgerFacade) -> T): T {
        val holder =
            checkNotNull(ledgerProcessHolderFor(context)) {
                "the launched app must expose the process-scoped runtime holder"
            }
        val scope = checkNotNull(holder.ledger) { "the launched runtime must have reached Ready" }
        val port =
            checkNotNull(scope.accountReconciliationRead) {
                "the launched composition must wire the account reconciliation read port"
            }
        return when (val outcome = scope.leased { facade, _ -> block(port, facade) }) {
            is LeaseOutcome.Completed -> outcome.value
            LeaseOutcome.NotReady -> error("the launched runtime is not Ready; the reconciliation read cannot run")
        }
    }

    /** The first queue page of [accountId] (the read the entry-A host performs on open). */
    fun queuePage(accountId: AccountId): P801AccountReconciliationPageResult {
        val ledgerId = read { _, facade -> facade.ledgerId }
        val result = read { port, _ -> port.loadAccountReconciliationPage(ledgerId, accountId, null) }
        when (result) {
            is P801AccountReconciliationPageResult.Success ->
                log("$tag port page rows=${result.page.rows.size} cursor=${result.page.nextCursor != null}")
            is P801AccountReconciliationPageResult.Failed ->
                log("$tag port page failed=${result.failure}")
        }
        return result
    }

    /** The whole-account totals of [accountId]. */
    fun summary(accountId: AccountId): P801AccountReconciliationSummaryResult {
        val ledgerId = read { _, facade -> facade.ledgerId }
        val result = read { port, _ -> port.loadAccountReconciliationSummary(ledgerId, accountId) }
        when (result) {
            is P801AccountReconciliationSummaryResult.Success ->
                log("$tag port summary totals=[" + p807Q2Flow2TotalLines(result.totals).joinToString(";") + "]")
            is P801AccountReconciliationSummaryResult.Failed ->
                log("$tag port summary failed=${result.failure}")
        }
        return result
    }

    /** The evidence row by identity (its projection state is the bind step's gate, `P503ReconciliationPresentation.kt:562-568`). */
    fun evidence(evidenceId: String): P801ReconciliationCandidateEvidence? {
        val ledgerId = read { _, facade -> facade.ledgerId }
        val result = read { port, _ -> port.loadCandidateEvidenceById(ledgerId, evidenceId) }
        val rows =
            when (result) {
                is P801CandidateEvidenceResult.Success -> result.rows
                is P801CandidateEvidenceResult.Failed -> error("the evidence read of $evidenceId failed: ${result.failure}")
            }
        val row = rows.firstOrNull()
        log(
            "$tag port evidence id=$evidenceId rows=${rows.size} projection=" +
                (row?.projection?.state?.storageValue ?: "none") + " activeLink=" + (row?.hasActiveEvidenceLink ?: false),
        )
        return row
    }

    /** The leg's audit read-back (the spec section 2.6 face). */
    fun audit(postingId: PostingId): P801ReconciledLegAudit {
        val ledgerId = read { _, facade -> facade.ledgerId }
        val result = read { port, _ -> port.loadReconciledLegAudit(ledgerId, postingId) }
        return when (result) {
            is P801ReconciledLegAuditResult.Success -> result.audit
            P801ReconciledLegAuditResult.NotFound -> error("the audited leg ${postingId.value} is not in this ledger")
            is P801ReconciledLegAuditResult.Failed -> error("the audit read of ${postingId.value} failed: ${result.failure}")
        }
    }

    /** How many ACTIVE evidence links the leg carries (the audit's own state field, spec section 2.6). */
    fun activeLinkCount(postingId: PostingId): Int = audit(postingId).evidence.count { link -> link.state == "active" }

    /** The import-review row of one candidate, or a hard failure when the review read cannot answer. */
    fun reviewRow(candidateId: String): ImportReviewRow {
        val row =
            P807Q2Flow1Ledger(context).read { facade ->
                val rowsRead =
                    checkNotNull(facade.queryImportReviewRows) {
                        "the launched graph must wire the import review read"
                    }
                when (val page = rowsRead.queryPage(facade.ledgerId, 50L, null)) {
                    is ImportReviewRowsPageResult.Page -> page.rows.firstOrNull { candidate -> candidate.candidateId.value == candidateId }
                    ImportReviewRowsPageResult.Unavailable -> error("the import review read answered Unavailable")
                }
            }
        return checkNotNull(row) { "the import review list must carry candidate $candidateId" }
    }
}

/**
 * The flow's reconciliation + import-review UI driver. The generic app discipline (launch, seek,
 * click, absence sampling, failure dumps) stays in the frozen [P807Q2Flow1Ui]; this class adds the
 * steps and anchors this flow needs — every radio button on the reconciliation surface has NO text
 * and NO contentDescription (`P503ReconciliationScreen.kt:378-382,391-395,434-438`), so each one is
 * anchored structurally on its own visible label, the P807Q2Flow1DeviceSupport.kt:1325-1342 shape.
 */
internal class P807Q2Flow2Ui(
    private val flow: P807Q2Flow1Ui,
    private val log: (String) -> Unit,
) {
    private val tag = P807Q2Flow2Facts.PREFIX

    // ---------------------------------------------------------------- navigation

    fun launch() {
        flow.launch()
    }

    fun awaitHome(): Boolean = flow.awaitHomeCapsule()

    /** The dock's ACCOUNTS tab, then the accounts region's grouped heading (the asset rows' surface). */
    fun openAccountsTab() {
        flow.clickApp(P807Q2Flow2Facts.ACCOUNT_TAB)
        flow.awaitAppText(P807Q2Flow2Facts.ACCOUNTS_GROUP_PREFIX)
    }

    /** The accounts region's account row opens the account-flow surface (`P503AssetsScreen.kt:296-312`). */
    fun openAccountFlow(accountName: String) {
        flow.seekApp(accountName)
        val row =
            checkNotNull(flow.findAppNode { node -> flow.textOf(node) == accountName }) {
                "the accounts region must carry the row [$accountName]"
            }
        log("$tag account row " + describe(row))
        flow.tap(row)
        flow.awaitAppText(P807Q2Flow2Facts.ACCOUNT_FLOW_TITLE)
    }

    /**
     * The account-flow header's 账户对账 action (`P503AccountFlowScreen.kt:97-102`) opens the queue,
     * and the queue's own partition header [partitionHeader] must render.
     */
    fun openQueue(partitionHeader: String) {
        flow.clickApp(P807Q2Flow2Facts.RECONCILIATION_TITLE)
        flow.awaitAppText(partitionHeader)
    }

    /** Clicks the surface's own 返回 and waits for [marker] on the surface it lands on. */
    fun backTo(marker: String) {
        flow.clickApp(P807Q2Flow2Facts.BACK_LABEL)
        flow.awaitAppText(marker)
    }

    /** The drawer's 导入账单 row; the review list loads on the landed surface (`P503App.kt:4152-4157`). */
    fun openImportReview() {
        if (!flow.hasAppText(P807Q2Flow2Facts.REVIEW_PENDING_HEADER)) {
            flow.openDrawerRow(P807Q2Flow2Facts.DRAWER_IMPORT_ROW)
        }
        flow.awaitAppText(P807Q2Flow2Facts.REVIEW_PENDING_HEADER)
        flow.awaitAppText(P807Q2Flow2Facts.REVIEW_CANDIDATE_AMOUNT)
    }

    /** The dock's HOME tab (leaves the drawer-only import surface, `P503App.kt:5404-5409`). */
    fun goHome() {
        flow.clickApp(P807Q2Flow2Facts.HOME_TAB)
        check(flow.awaitHomeCapsule()) { "the HOME tab must land on the overview with its search capsule" }
    }

    // ---------------------------------------------------------------- entry B: evidence anchor + bind

    /** The candidate row's 用于核对既有分录 action (`P503ImportReview.kt:874-880`) opens the evidence anchor. */
    fun openEvidenceEntry() {
        flow.clickApp(P807Q2Flow2Facts.EVIDENCE_ENTRY_TITLE)
        flow.awaitAppText(P807Q2Flow2Facts.BIND_HEADER)
    }

    /**
     * Selects [accountName] as the bind target and binds. The account row is
     * `Row { RadioButton(...); Text(choice.name) }` (`P503ReconciliationScreen.kt:390-397`), so the
     * radio is named by the row's own structure first and by the Row's fixed radio-then-label
     * geometry only when the tree will not expose it ([selectRadio]). The bind action must be
     * UNAVAILABLE before the pick (`reconciliationBindEnabled`,
     * `P503ReconciliationPresentation.kt:583`) and enabled after it; both states are read off the
     * button node itself, never inferred. Every failure dumps the surface first and carries the
     * anchor report in its message, so the next run names its own cause instead of costing a round.
     */
    fun bindToAccount(accountName: String): Boolean {
        if (!awaitButtonVisible(P807Q2Flow2Facts.BIND_BUTTON)) {
            flow.dumpUiSnapshot("bindToAccount:button")
            return false
        }
        if (bindButtonEnabled()) {
            flow.dumpUiSnapshot("bindToAccount:initial-enabled")
            error(
                "the bind button must be unavailable before an account is selected; " +
                    anchorReport("bind-initial", accountName, null),
            )
        }
        if (!flow.hasAppText(accountName)) flow.scrollBackTo(accountName)
        val label =
            flow.findAppNode { node -> flow.textOf(node) == accountName }
                ?: run {
                    val report = anchorReport("bind-label", accountName, null)
                    flow.dumpUiSnapshot("bindToAccount:label")
                    log("$tag bind label=absent account=$accountName bindEnabledBefore=false $report")
                    error("the bind step must offer the account [$accountName]; $report")
                }
        val path = selectRadio(label, "bindToAccount:radio", accountName)
        log(
            "$tag bind anchor account=$accountName $path label=" + describe(label) +
                " bindEnabledBefore=false",
        )
        // The bind button's own text is a PREFIX of 绑定到账户, so the seek uses the unique warning
        // copy that sits between the radios and the button; the section fits one screen, so this is
        // normally a no-op.
        runCatching { flow.seekApp(P807Q2Flow2Facts.BIND_IRREVERSIBLE_WARNING) }
        if (!awaitEnabledButton(P807Q2Flow2Facts.BIND_BUTTON)) {
            val report = anchorReport("bind-not-selected", accountName, label)
            flow.dumpUiSnapshot("bindToAccount:enabled")
            log("$tag bind selected=false account=$accountName $path bindEnabledAfter=false $report")
            error("the account pick did not enable 绑定 for [$accountName] ($path); $report")
        }
        log("$tag bind selected account=$accountName $path bindEnabledAfter=true")
        flow.clickApp(P807Q2Flow2Facts.BIND_BUTTON)
        flow.awaitAppText(P807Q2Flow2Facts.BOUND_NOTICE)
        return true
    }

    /** Whether the bind button's clickable node currently reports enabled. */
    fun bindButtonEnabled(): Boolean = clickableFor(P807Q2Flow2Facts.BIND_BUTTON)?.isEnabled == true

    /** Whether the bind section's irreversible warning is on screen (spec section 2.4: before the action). */
    fun bindWarningVisible(): Boolean = flow.hasAppText(P807Q2Flow2Facts.BIND_IRREVERSIBLE_WARNING)

    // ---------------------------------------------------------------- candidates + confirm (entry B)

    /**
     * Selects the single proposed candidate. Its radio carries no text of its own, so it is named by
     * the candidate row's structure, anchored on the candidate's own line
     * ([P807Q2Flow2Facts.CANDIDATE_LINE]) exactly as [bindToAccount] names the account radio. The
     * postcondition is the four-element block, which only renders for a valid selection
     * (`reconciliationSelectedPair`, `P503ReconciliationScreen.kt:459-464`).
     */
    fun selectCandidate(): Boolean {
        flow.seekApp(P807Q2Flow2Facts.CANDIDATE_LINE)
        val anchorText = P807Q2Flow2Facts.CANDIDATE_LINE
        val label =
            flow.findAppNode { node -> flow.textOf(node) == anchorText }
                ?: run {
                    val report = anchorReport("candidate-label", anchorText, null)
                    flow.dumpUiSnapshot("selectCandidate:label")
                    log("$tag select candidate label=absent line=[$anchorText] $report")
                    error("the proposal must render its candidate line [$anchorText]; $report")
                }
        val path = selectRadio(label, "selectCandidate:radio", anchorText)
        log("$tag select candidate $path label=" + describe(label))
        return runCatching { flow.awaitAppText(P807Q2Flow2Facts.ELEMENT_EXTERNAL_EVIDENCE) }.isSuccess
    }

    /**
     * Clicks 确认核对 and waits for the entry-B success notice. The button is clicked only while it
     * reports enabled, so a disabled state (no valid selection / a write in flight) fails here
     * instead of silently doing nothing.
     */
    fun confirm(): Boolean {
        flow.seekApp(P807Q2Flow2Facts.CONFIRM_BUTTON)
        if (!awaitEnabledButton(P807Q2Flow2Facts.CONFIRM_BUTTON)) {
            flow.dumpUiSnapshot("confirm:disabled")
            return false
        }
        flow.clickApp(P807Q2Flow2Facts.CONFIRM_BUTTON)
        return runCatching { flow.awaitAppText(P807Q2Flow2Facts.CONFIRMED_NOTICE_EVIDENCE_TARGET) }.isSuccess
    }

    // ---------------------------------------------------------------- queue: checked partition + audit

    /** Expands the collapsed checked partition (`P503ReconciliationScreen.kt:144-152`). */
    fun expandCheckedPartition(): Boolean {
        flow.seekApp(P807Q2Flow2Facts.CHECKED_ONE_COLLAPSED)
        flow.clickApp(P807Q2Flow2Facts.CHECKED_ONE_COLLAPSED)
        return runCatching { flow.awaitAppText(P807Q2Flow2Facts.QUEUE_ROW_CHECKED) }.isSuccess
    }

    /** Opens the leg's candidate/audit surface from the queue row (`P503ReconciliationScreen.kt:184`). */
    fun openLegAudit(): Boolean {
        flow.seekApp(P807Q2Flow2Facts.QUEUE_ROW_CHECKED)
        val row =
            checkNotNull(flow.findAppNode { node -> flow.textOf(node) == P807Q2Flow2Facts.QUEUE_ROW_CHECKED }) {
                "the checked partition must render the leg's own row"
            }
        flow.tap(row)
        return runCatching { flow.awaitAppText(P807Q2Flow2Facts.AUDIT_HEADER) }.isSuccess
    }

    // ---------------------------------------------------------------- entry B again: the refusal leg

    /** The review candidate's row (by its exact amount line) opens the candidate detail. */
    fun openCandidateDetail() {
        flow.seekApp(P807Q2Flow2Facts.REVIEW_CANDIDATE_AMOUNT)
        val amount =
            checkNotNull(flow.findAppNode { node -> flow.textOf(node) == P807Q2Flow2Facts.REVIEW_CANDIDATE_AMOUNT }) {
                "the review list must render the seeded candidate's amount line"
            }
        flow.tap(amount)
        flow.awaitAppText(P807Q2Flow2Facts.CANDIDATE_DETAIL_TITLE)
    }

    /**
     * Completes the seeded `transfer_flow` candidate's decision from the candidate detail: one
     * 转出账户 and one 转入账户 option. Each option is a `○ <name>` TextButton
     * (`P503ImportReview.kt:1151-1166`), and the same account names appear under BOTH sections, so
     * each pick is anchored on its own section label and takes the nearest option BELOW it — the
     * "same vertical band label + nearest clickable node" discipline, vertically.
     */
    fun completeTransferDecision(
        fromAccountName: String,
        toAccountName: String,
    ): Boolean {
        if (!awaitDecisionForm()) {
            flow.dumpUiSnapshot("completeTransferDecision:form")
            return false
        }
        if (!pickDecisionOption(P807Q2Flow2Facts.FROM_ACCOUNT_SECTION, fromAccountName)) return false
        if (!pickDecisionOption(P807Q2Flow2Facts.TO_ACCOUNT_SECTION, toAccountName)) return false
        return runCatching { flow.awaitAppText(P807Q2Flow2Facts.DECISION_COMPLETE) }.isSuccess
    }

    /**
     * The candidate detail's own selection row: the checkbox anchored on the detail-only hint copy
     * (`P503ImportReview.kt:1064-1071`), exactly the frozen flow-1 anchoring rule. The postcondition
     * is the detail's 进入批量确认 entry, which only renders for a non-empty selection
     * (`P503ImportReview.kt:1035-1047`).
     */
    fun selectForBatch(): Boolean {
        var lastHint: AccessibilityNodeInfo? = null
        repeat(SELECTION_ATTEMPTS) { attempt ->
            flow.seekApp(P807Q2Flow2Facts.SELECTION_HINT)
            val hint = flow.findAppNode { node -> flow.textOf(node) == P807Q2Flow2Facts.SELECTION_HINT }
            lastHint = hint
            val checkbox = hint?.let(::checkboxLeftOfHint)
            if (hint == null || checkbox == null) {
                log("$tag selection row absent attempt=$attempt hint=${hint != null}")
                flow.settle()
                return@repeat
            }
            log("$tag selection checkbox attempt=$attempt " + describe(checkbox) + " hint=" + flow.bounds(hint))
            flow.tap(checkbox)
            if (runCatching { flow.awaitAppText(P807Q2Flow2Facts.BATCH_ENTRY) }.isSuccess) return true
        }
        val report = anchorReport("selectForBatch", P807Q2Flow2Facts.SELECTION_HINT, lastHint)
        flow.dumpUiSnapshot("selectForBatch")
        log("$tag selection row not selectable attempts=$SELECTION_ATTEMPTS $report")
        return false
    }

    /** Determinately clicks the detail's 进入批量确认 and the authorization page's 确认入账（1 项）. */
    fun authorizeBatch(): Boolean {
        if (!runCatching { flow.awaitAppText(P807Q2Flow2Facts.BATCH_ENTRY) }.isSuccess) {
            flow.dumpUiSnapshot("authorizeBatch:entry")
            return false
        }
        flow.clickApp(P807Q2Flow2Facts.BATCH_ENTRY)
        if (!runCatching { flow.awaitAppText(P807Q2Flow2Facts.BATCH_TITLE) }.isSuccess) {
            flow.dumpUiSnapshot("authorizeBatch:title")
            return false
        }
        flow.seekApp(P807Q2Flow2Facts.BATCH_AUTHORIZE_ONE)
        if (!awaitEnabledButton(P807Q2Flow2Facts.BATCH_AUTHORIZE_ONE)) {
            flow.dumpUiSnapshot("authorizeBatch:authorize")
            return false
        }
        flow.clickApp(P807Q2Flow2Facts.BATCH_AUTHORIZE_ONE)
        return true
    }

    /**
     * Returns from the authorization page to the import review list. The authorize does not
     * reliably bounce the client back on its own (the flow-1 observation this flow inherits), so
     * the return is explicit and bounded: click the page's own 返回 until the review list is
     * visible again.
     */
    fun returnToReviewList(): Boolean {
        repeat(SELECTION_ATTEMPTS) { attempt ->
            if (flow.hasAppText(P807Q2Flow2Facts.REVIEW_PENDING_HEADER)) {
                log("$tag review list reached attempt=$attempt")
                return true
            }
            val back = flow.findAppNode { node -> flow.textOf(node) == P807Q2Flow2Facts.BACK_LABEL }
            if (back != null) {
                flow.tap(back)
            } else {
                flow.back()
            }
            if (runCatching { flow.awaitAppText(P807Q2Flow2Facts.REVIEW_PENDING_HEADER) }.isSuccess) return true
            flow.settle()
        }
        flow.dumpUiSnapshot("returnToReviewList")
        return false
    }

    // ---------------------------------------------------------------- shared plumbing

    /** Generic re-exports the flow's assertions read off the same driver (no second UI discipline). */
    fun hasText(text: String): Boolean = flow.hasAppText(text)

    fun awaitNoText(prefix: String): String? = flow.awaitNoAppText(prefix)

    fun seek(text: String) {
        flow.seekApp(text)
    }

    fun dump(label: String) {
        flow.dumpUiSnapshot(label)
    }

    /** The visible node whose text or contentDescription is exactly [text], if any. */
    fun nodeWithText(text: String): AccessibilityNodeInfo? = flow.findAppNode { node -> flow.textOf(node) == text || flow.descriptionOf(node) == text }

    /** Every visible node's text, used by the flow to assert a summary line's tail (counts) and absences. */
    fun visibleTexts(): List<String> = flow.appNodes().map(flow::textOf).filter { text -> text.isNotBlank() }

    private fun awaitDecisionForm(timeoutMillis: Long = DECISION_FORM_TIMEOUT_MILLIS): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { flow.seekApp(P807Q2Flow2Facts.FROM_ACCOUNT_SECTION) }.isSuccess) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    /**
     * One decision option of the section [section]: the nearest `○ <name>` / `● <name>` button
     * BELOW the section label (each section renders its own option list, so the same name appears
     * under more than one label and only the vertical band disambiguates).
     */
    private fun pickDecisionOption(
        section: String,
        accountName: String,
    ): Boolean {
        var lastLabel: AccessibilityNodeInfo? = null
        repeat(SELECTION_ATTEMPTS) { attempt ->
            runCatching { flow.seekApp(section) }
            val anchor = flow.findAppNode { node -> flow.textOf(node) == section }
            lastLabel = anchor
            val band = flow.bounds(anchor ?: return@repeat)
            val options =
                flow
                    .appNodes()
                    .filter { node ->
                        val text = flow.textOf(node)
                        (text == "○ " + accountName || text == "● " + accountName) && flow.bounds(node).centerY() > band.bottom
                    }.sortedBy { node -> flow.bounds(node).centerY() }
            val option = options.firstOrNull()
            if (option == null) {
                log("$tag decision option absent section=$section account=$accountName attempt=$attempt")
                flow.settle()
                return@repeat
            }
            log("$tag decision option section=$section " + describe(option))
            flow.tap(option)
            SystemClock.sleep(POLL_MILLIS)
            if (flow.findAppNode { node -> flow.textOf(node) == "● " + accountName } != null) {
                log("$tag decision option selected section=$section account=$accountName")
                return true
            }
        }
        val report = anchorReport("pickDecisionOption:$section", section, lastLabel)
        flow.dumpUiSnapshot("pickDecisionOption:$section")
        log("$tag decision option not selected section=$section account=$accountName attempts=$SELECTION_ATTEMPTS $report")
        return false
    }

    /**
     * The radio that belongs to [label]'s own row, found by STRUCTURE. Both radio rows in this
     * product are `Row { RadioButton(...); Text(...) }` (`P503ReconciliationScreen.kt:390-397` for
     * the account choice, `:433-440` for a candidate), so the radio is the label's sibling inside the
     * nearest ancestor that holds a checkable node, and no coordinate is needed to name it. The
     * previous anchor demanded `radio.right <= label.left` on the same band, which loses the radio
     * whenever the client tree reports a bounds rectangle the layout no longer has (this stack
     * documents stale client trees, `AndroidScaleUi.kt:137-163`); a row whose label was composed has
     * its radio beside it.
     */
    private fun radioOfRow(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val band = flow.bounds(label)
        var current: AccessibilityNodeInfo? = label.parent
        repeat(ANCESTOR_DEPTH) {
            val container = current ?: return null
            val radios =
                collectNodes(container).filter { node ->
                    node != label && node.isVisibleToUser && isRadioNode(node) && onBand(node, band)
                }
            if (radios.size == 1) return radios.single()
            if (radios.isNotEmpty()) {
                return radios
                    .minByOrNull { node -> abs(flow.bounds(node).centerY() - band.centerY()) }
            }
            current = container.parent
        }
        return null
    }

    /** Whether [node] is radio-like: checkable, selected, or the platform's radio/check class. */
    private fun isRadioNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isCheckable ||
            node.isChecked ||
            node.isSelected ||
            className.endsWith("RadioButton") ||
            className.endsWith("CheckBox")
    }

    /** Whether [node]'s centre sits on [band]'s row, with the slack a re-measured row needs. */
    private fun onBand(
        node: AccessibilityNodeInfo,
        band: Rect,
    ): Boolean {
        val candidate = flow.bounds(node)
        return candidate.centerY() in (band.top - BAND_SLACK_PX)..(band.bottom + BAND_SLACK_PX) && candidate.width() > 0
    }

    /**
     * The radio's screen point derived from [label]'s own bounds: one radio column left of the label,
     * on the label's vertical centre. WHY THE COORDINATE IS SOUND HERE: the Row's child order is
     * fixed radio-then-label with no horizontal arrangement (`P503ReconciliationScreen.kt:390-396`),
     * and a Material3 radio's touch target is [RADIO_SLOT_DP] dp wide, so the radio's centre sits
     * exactly that far left of the label's left edge. This is the fallback for a tree that will not
     * name the radio node at all; it is never trusted on its own — the caller accepts it only when the
     * action it enables (绑定 / 确认核对) flips from unavailable to available, so a tap on the wrong
     * pixel fails the leg instead of passing it.
     */
    private fun radioSlotLeftOf(label: AccessibilityNodeInfo): Pair<Int, Int>? {
        val band = flow.bounds(label)
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        val x = band.left - (RADIO_SLOT_DP * density).toInt()
        return if (x > 0) x to band.centerY() else null
    }

    /** Injects one touchscreen tap at [point] (the D-217 coordinate path `AndroidScaleUi.clickNode` uses). */
    private fun tapAt(point: Pair<Int, Int>) {
        val (x, y) = point
        val down = SystemClock.uptimeMillis()
        val events =
            listOf(
                MotionEvent
                    .obtain(down, down, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN },
                MotionEvent
                    .obtain(down, down + TOUCH_HOLD_MILLIS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN },
            )
        try {
            events.forEach { event ->
                check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) {
                    "coordinate tap injection refused at ($x, $y)"
                }
            }
        } finally {
            events.forEach { event -> event.recycle() }
        }
        SystemClock.sleep(180)
    }

    /**
     * Selects the radio of [label]'s row and returns the path it used. Structure first
     * ([radioOfRow]); coordinates second ([radioSlotLeftOf]), because a radio the tree will not name
     * still has exactly one place it can be. A failure dumps the surface and throws with the anchor
     * report, so the run names its own cause instead of costing another round.
     */
    private fun selectRadio(
        label: AccessibilityNodeInfo,
        step: String,
        anchorText: String,
    ): String {
        val radio = radioOfRow(label)
        if (radio != null) {
            flow.tap(radio)
            return "path=row radio=" + describe(radio)
        }
        log("$tag radio search step=$step anchor=[$anchorText] row=none")
        val slot = radioSlotLeftOf(label)
        if (slot == null) {
            flow.dumpUiSnapshot(step)
            error("no radio node and no derivable slot for [$anchorText]; " + anchorReport(step, anchorText, label))
        }
        tapAt(slot)
        log("$tag radio tap step=$step anchor=[$anchorText] path=coordinate slot=(" + slot.first + "," + slot.second + ")")
        return "path=coordinate slot=(" + slot.first + "," + slot.second + ")"
    }

    /**
     * The self-evidence of a failed anchor: the label itself, every node of its parent subtree with
     * the accessibility facts that decide radio-ness (class, text, enabled, clickable, checkable,
     * selected, bounds), and the screen's own visible texts. Truncation is MARKED: a report that says
     * it was cut is evidence; a report that silently stops is not.
     */
    private fun anchorReport(
        step: String,
        anchorText: String,
        label: AccessibilityNodeInfo?,
    ): String {
        val lines = ArrayList<String>()
        lines.add("anchor-report step=$step anchor=[$anchorText]")
        if (label == null) {
            lines.add("anchor=ABSENT (no visible node carries that exact text)")
        } else {
            lines.add("anchor=" + describe(label))
            val parent = label.parent
            if (parent == null) {
                lines.add("parent=ABSENT (the client tree answered no parent for the anchor)")
            } else {
                val subtree = collectNodes(parent)
                lines.add("parent-subtree nodes=" + subtree.size)
                subtree.forEach { node -> lines.add("  " + describe(node)) }
            }
        }
        val texts =
            flow
                .appNodes()
                .map { node -> flow.textOf(node) }
                .filter { text -> text.isNotBlank() }
                .distinct()
        lines.add("visible-texts count=" + texts.size + " [" + texts.joinToString(" ¦ ") + "]")
        return reportOf(lines)
    }

    /** Joins [lines], cutting at [MAX_REPORT_LINES] with an explicit marker instead of stopping silently. */
    private fun reportOf(lines: List<String>): String {
        if (lines.size <= MAX_REPORT_LINES) return lines.joinToString("\n")
        val kept = lines.take(MAX_REPORT_LINES)
        return (kept + "… truncated at " + MAX_REPORT_LINES + " of " + lines.size + " lines").joinToString("\n")
    }

    /**
     * The detail's selection checkbox: the 勾选候选 node that belongs to the hint's row. The checkbox
     * carries its own description (`P503ImportReview.kt:1064-1071`), so the description names it and
     * the row's band only breaks a tie between several checkboxes; the previous tie-break also
     * demanded `checkbox.right <= hint.left`, which is the same geometric assumption that lost the
     * account radio and is not needed once the description already names the control.
     */
    private fun checkboxLeftOfHint(hint: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val band = flow.bounds(hint)
        var current: AccessibilityNodeInfo? = hint.parent
        repeat(ANCESTOR_DEPTH) {
            val container = current ?: return null
            val boxes =
                collectNodes(container).filter { node ->
                    flow.descriptionOf(node) == P807Q2Flow2Facts.CHECKBOX_DESCRIPTION && node.isVisibleToUser
                }
            if (boxes.size == 1) return boxes.single()
            if (boxes.isNotEmpty()) {
                return boxes
                    .filter { node -> onBand(node, band) }
                    .minByOrNull { node -> abs(flow.bounds(node).centerY() - band.centerY()) }
            }
            current = container.parent
        }
        return null
    }

    /** The clickable node that carries [text] (exact text or description), or null. */
    private fun clickableFor(text: String): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = nodeWithText(text) ?: return null
        repeat(ANCESTOR_DEPTH) {
            val current = node ?: return null
            if (current.isClickable) return current
            node = current.parent
        }
        return null
    }

    /**
     * Waits for the button carrying [text] to exist (and, with [awaitingEnabled], to report enabled).
     * A button that never appears and an enabled-state wait are different proofs, so the two are
     * separate arguments rather than one silent loop.
     */
    private fun awaitEnabledButton(
        text: String,
        awaitingEnabled: Boolean = true,
        timeoutMillis: Long = ENABLED_TIMEOUT_MILLIS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val button = clickableFor(text)
            if (button != null && (!awaitingEnabled || button.isEnabled)) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    /** Waits for the button carrying [text] to be on screen, paying one forward seek if it sits below the fold. */
    private fun awaitButtonVisible(
        text: String,
        timeoutMillis: Long = ENABLED_TIMEOUT_MILLIS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var seeked = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (clickableFor(text) != null) return true
            if (!seeked && SystemClock.elapsedRealtime() > deadline - SEEK_AFTER_MILLIS) {
                seeked = true
                runCatching { flow.seekApp(text) }
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun describe(node: AccessibilityNodeInfo): String =
        "class=" + node.className + " clickable=" + node.isClickable + " enabled=" + node.isEnabled +
            " checkable=" + node.isCheckable + " checked=" + node.isChecked + " selected=" + node.isSelected +
            " bounds=" + flow.bounds(node) + " text=[" + flow.textOf(node) + "] desc=[" + flow.descriptionOf(node) + "]"

    /** Breadth-first walk of [root], bounded (the flow-1 collector's shape; no new UI discipline). */
    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return result
    }

    private companion object {
        /** How many ancestors a structural anchor may climb (the flow-1 anchor's bound). */
        const val ANCESTOR_DEPTH = 5

        /** The bounded tree walk of one anchor lookup. */
        const val MAX_TREE_NODES = 2_000

        /** The vertical slack one anchor tolerates around its row band (px). */
        const val BAND_SLACK_PX = 16

        /** The radio centre's offset from its label's left edge, in dp (a Material3 radio touch target). */
        const val RADIO_SLOT_DP = 24

        /** How long one injected tap holds the touch down. */
        const val TOUCH_HOLD_MILLIS = 60L

        /** The most lines one anchor report may carry before it is cut with a marker. */
        const val MAX_REPORT_LINES = 60

        /** The selection/decision click retries (the frozen flow-1 `SELECTION_CLICK_ATTEMPTS` value). */
        const val SELECTION_ATTEMPTS = 3

        /** The decision form waits for the cached catalog snapshot to land (`P503ImportReview.kt:984-1009`). */
        const val DECISION_FORM_TIMEOUT_MILLIS = 60_000L

        /** How long a button may take to appear / report enabled after its enabling event. */
        const val ENABLED_TIMEOUT_MILLIS = 30_000L

        /** When the appearance wait pays its one forward seek. */
        const val SEEK_AFTER_MILLIS = 20_000L

        /** The UI poll interval (the frozen flow-1 `POLL_MILLIS` value). */
        const val POLL_MILLIS = 500L
    }
}

/**
 * The status-history line of one `posting_reconciliation_history` entry, mirroring
 * `reconciliationAuditStatusHistoryText` (`P503ReconciliationPresentation.kt:460`):
 * `#<sequence> <label> <occurredAt>`.
 */
internal fun p807Q2Flow2StatusHistoryLine(
    sequence: Long,
    status: P408ReconciliationStatus,
    occurredAt: String,
): String = "#$sequence " + status.label + " " + occurredAt

/**
 * The accumulated-total face of one summary read as the flow compares it: the `(currency, status)`
 * groups the port returned, in the read's own order, as exact integer minor units.
 */
internal fun p807Q2Flow2TotalLines(totals: List<P801AccountReconciliationStatusTotal>): List<String> = totals.map { total -> total.currency.code + "|" + total.status.storageValue + "|" + total.totalMinorUnits + "|" + total.rowCount }

/**
 * The queue row's own comparable face, derived from the PORT payload through the same fields
 * `reconciliationQueueRowText` renders (`P503ReconciliationPresentation.kt:170-174`), so the UI
 * line is compared against an independently built expectation rather than against itself.
 */
internal fun p807Q2Flow2QueueRowText(row: P801AccountReconciliationQueueRow): String =
    row.occurredAt.toString().take(10) + " · " + (row.note ?: row.transactionKind.name) + " · " +
        p807Q2Flow2MinorText(row.amountMinor, row.currency.precision) + " " + row.currency.code + " · " + row.status.label

/** Exact decimal text of [minor] at [precision], through integer arithmetic only (`formatMinorUnits`' rule). */
internal fun p807Q2Flow2MinorText(
    minor: Long,
    precision: Int,
): String {
    if (precision <= 0) return minor.toString()
    val magnitude = if (minor < 0) -minor else minor
    var scale = 1L
    repeat(precision) { scale *= 10L }
    val whole = magnitude / scale
    val fraction = (magnitude % scale).toString().padStart(precision, '0')
    return (if (minor < 0) "-" else "") + whole.toString() + "." + fraction
}

/** Whether a read evidence row carries a READY projection (the bind step's own gate). */
internal fun p807Q2Flow2ProjectionReady(evidence: P801ReconciliationCandidateEvidence): Boolean = evidence.projection?.state == P408ProjectionState.READY
