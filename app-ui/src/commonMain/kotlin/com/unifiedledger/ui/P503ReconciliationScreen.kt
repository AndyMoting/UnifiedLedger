package com.unifiedledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MatchCandidate
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801CandidateProposal
import com.unifiedledger.application.P801CandidateProposalResult
import com.unifiedledger.application.P801ReconciledLegAudit
import com.unifiedledger.application.P801ReconciledLegAuditResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.ui.theme.LocalP8Palette

/**
 * P8-01a i3a (D-308 items 2/6/8; spec sections 2.2/2.3): the account reconciliation queue, the
 * 「账户对账」 surface reached from the account-flow header (entry A). The skeleton is the existing
 * account-flow screen's (header row + summary block + rows + footer + typed failure surface), and
 * the data is the i1 read face: one keyset page per read plus the whole-account per-(currency,
 * status) totals.
 *
 * 诚实呈现. The two reads fail INDEPENDENTLY (V-9): a failed summary renders its own failure line and
 * keeps the loaded rows, a failed page read keeps the rows it already has beside the retry, and
 * neither failure is ever presented as a zero total or an empty account. The totals use only the
 * spec's 已核对 / 未核对 / 条数 vocabulary — no balance-derived difference exists anywhere on this
 * surface (D-308 item 6).
 *
 * 已核对 partitioning (spec section 2.3): the unchecked legs come first, the checked legs sit in
 * their own partition, collapsed by default and expanded on demand. Every row is a leg-level read —
 * the surface has zero write entries; the only exits are 返回 and the row open (a read).
 *
 * 1.3x font (§6 device debt): no row has a fixed height band; text grows the rows (the b4
 * font-scale convention), and the body scrolls so every row stays reachable.
 */
@Composable
internal fun P503ReconciliationQueueScreen(
    state: P503AppState.ReconciliationQueue,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onToggleCheckedPartition: () -> Unit,
    onOpenLeg: ((P801AccountReconciliationQueueRow) -> Unit)? = null,
    registerListScroll: ((androidx.compose.foundation.ScrollState) -> Unit)? = null,
    restoreScrollTo: ListScrollPosition? = null,
    onScrollRestored: () -> Unit = {},
) {
    val palette = LocalP8Palette.current
    // 返回保滚动位: the surface's scroll state is hoisted so the sampled position, the host sampler
    // and the restore all address the same object (the b3 accounts-list mechanism, reused as-is).
    val listScrollState = rememberScrollState()
    LaunchedEffect(registerListScroll) { registerListScroll?.invoke(listScrollState) }
    val pendingRestore = restoreScrollTo
    LaunchedEffect(pendingRestore) {
        if (pendingRestore != null) {
            runCatching { listScrollState.scrollTo(pendingRestore.firstVisibleItemScrollOffset) }
            onScrollRestored()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(listScrollState).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(RECONCILIATION_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            state.accountLine,
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // 汇总: per (currency, status) totals. A failed aggregate renders its own failure line and
        // never a zero (spec section 2.3 / V-9); the rows stay visible either way.
        if (state.summaryLoadFailed) {
            Text(
                reconciliationSummaryFailureText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.semantics {
                        contentDescription = reconciliationSummaryFailureText()
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
        } else {
            reconciliationSummaryLines(state.totals).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        when (reconciliationQueueFace(state)) {
            ReconciliationQueueFace.UNAVAILABLE -> {
                Text(
                    reconciliationQueueFailureText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.semantics {
                            contentDescription = reconciliationQueueFailureText()
                            liveRegion = LiveRegionMode.Assertive
                        },
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onRetryLoadMore) { Text("重试") }
            }
            ReconciliationQueueFace.EMPTY ->
                Text(reconciliationQueueEmptyText(), style = MaterialTheme.typography.bodyLarge)
            ReconciliationQueueFace.LIST -> {
                Text(
                    reconciliationUncheckedPartitionText(state.uncheckedRows.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                state.uncheckedRows.forEach { row ->
                    ReconciliationQueueRowLine(row, onOpenLeg)
                    HorizontalDivider(color = palette.divider)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onToggleCheckedPartition, modifier = Modifier.fillMaxWidth()) {
                    Text(reconciliationCheckedPartitionText(state.checkedRows.size, state.checkedExpanded))
                }
                if (state.checkedExpanded) {
                    state.checkedRows.forEach { row ->
                        ReconciliationQueueRowLine(row, onOpenLeg)
                        HorizontalDivider(color = palette.divider)
                    }
                }
                // The queue footer is the account flow's footer (F12b: one implementation, one copy).
                P8FlowFooter(
                    state = reconciliationQueueFooterState(state),
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                )
            }
        }
    }
}

/**
 * One queue row: date · note-or-kind · exact signed amount and currency · five-state label (spec
 * section 2.3). The row opens the candidate/audit surface when the host wired it; the row is never
 * a write affordance.
 *
 * F12f: the click label is an ACTION (not a screen name) and the row sets NO contentDescription —
 * the visible line is already the accessibility text, so a mirrored label would only announce every
 * value twice.
 */
@Composable
private fun ReconciliationQueueRowLine(
    row: P801AccountReconciliationQueueRow,
    onOpenLeg: ((P801AccountReconciliationQueueRow) -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onOpenLeg != null) {
                        Modifier.clickable(onClickLabel = RECONCILIATION_OPEN_LEG_LABEL) { onOpenLeg(row) }
                    } else {
                        Modifier
                    },
                ).padding(vertical = 6.dp),
    ) {
        // The row states the leg's own five-state label; whether it already carries an active
        // evidence link is the audit read-back's business (spec section 2.6), so nothing is
        // duplicated here.
        Text(reconciliationQueueRowText(row), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * P8-01a i3a (D-308 items 2/3/5/7/10; spec sections 2.4/2.5/2.6): the candidate, bind, confirm and
 * audit surface of one anchor. The skeleton follows the existing correction/confirm screens: header
 * row + typed notice in the error colour with an ASSERTIVE live region + sections + the action row.
 *
 * 四要素 THEN THE CALL (spec section 2.5). Selecting a candidate renders 外部证据 / 证据职责 /
 * 匹配依据 / 人工决定 for THAT candidate; the confirm button exists only beside those four elements,
 * and it is disabled while the leg's responsibility already carries an active link (spec section
 * 2.4: 其余同职责证据一律呈现为不可确认). Nothing on this surface ranks or scores candidates — the
 * order is the use case's frozen presentation order and no confidence value is rendered.
 *
 * 绑定 (spec section 2.4). The irreversibility warning is printed BEFORE the action, and nothing is
 * materialized by browsing: the bind call happens only on the user's bind action. Entry B's target
 * account is an explicit choice; entry A (the leg anchor) lists the account/window's waiting
 * evidence and fixes the target to that leg's account, so 账户 → 队列 → 绑定 is reachable (V-10).
 * An empty bind list says exactly that — it never claims the window has no import rows.
 *
 * 审计回看 (spec section 2.6) is read-only: the evidence source, responsibility, five-field basis,
 * natural-day distance, the human decision with its time, and both histories with their sequence.
 */
@Composable
internal fun P503ReconciliationDetailScreen(
    state: P503AppState.ReconciliationDetail,
    onClose: () -> Unit,
    onSelectCandidate: (proposalIndex: Int, candidateIndex: Int) -> Unit,
    onSelectBindTarget: (AccountId) -> Unit,
    onSelectBindEvidence: (String) -> Unit,
    onBind: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalP8Palette.current
    val title = if (state.anchorEvidenceId != null && state.anchorPostingId == null) RECONCILIATION_EVIDENCE_ENTRY_TITLE else RECONCILIATION_TITLE
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
        state.notice?.let { notice ->
            val text = reconciliationNoticeText(notice)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.semantics {
                        contentDescription = text
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        ReconciliationAuditSection(state.audit, palette)
        if (reconciliationBindStepVisible(state)) {
            ReconciliationBindSection(state, onSelectBindTarget, onSelectBindEvidence, onBind)
        }
        Spacer(Modifier.height(8.dp))
        Text(RECONCILIATION_CANDIDATES_HEADER, style = MaterialTheme.typography.titleSmall)
        when (val proposals = state.proposals) {
            null -> Text(reconciliationNoCandidateEvidenceText(), style = MaterialTheme.typography.bodyMedium)
            is P801CandidateProposalResult.Failed ->
                Text(
                    // F2: a REJECTED projection keeps its stored code beside the label.
                    reconciliationProposalFailureText(proposals.reason, state.anchorEvidence?.projection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            is P801CandidateProposalResult.Success ->
                if (proposals.proposals.isEmpty()) {
                    Text(reconciliationNoCandidateEvidenceText(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    proposals.proposals.forEachIndexed { proposalIndex, proposal ->
                        ReconciliationProposalBlock(
                            proposalIndex = proposalIndex,
                            proposal = proposal,
                            state = state,
                            onSelectCandidate = onSelectCandidate,
                        )
                    }
                }
        }
        ReconciliationConfirmBlock(state, palette, onConfirm)
    }
}

/** The spec section 2.6 audit read-back: headline, then one block per evidence link. */
@Composable
private fun ReconciliationAuditSection(
    audit: P801ReconciledLegAuditResult?,
    palette: com.unifiedledger.ui.theme.P8Palette,
) {
    if (audit == null) return
    Text(RECONCILIATION_AUDIT_HEADER, style = MaterialTheme.typography.titleSmall)
    when (audit) {
        is P801ReconciledLegAuditResult.Failed ->
            Text(
                reconciliationAuditFailureText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        P801ReconciledLegAuditResult.NotFound ->
            Text(reconciliationAuditNotFoundText(), style = MaterialTheme.typography.bodyMedium)
        is P801ReconciledLegAuditResult.Success -> ReconciliationAuditBody(audit.audit, palette)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ReconciliationAuditBody(
    audit: P801ReconciledLegAudit,
    palette: com.unifiedledger.ui.theme.P8Palette,
) {
    Text(reconciliationAuditHeadlineText(audit), style = MaterialTheme.typography.bodyMedium)
    if (audit.evidence.isEmpty()) {
        Text(reconciliationAuditNoLinkText(), style = MaterialTheme.typography.bodySmall)
    }
    audit.evidence.forEach { link ->
        Text(
            reconciliationAuditSourceText(link),
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurfaceVariant,
        )
        reconciliationAuditDecisionLines(link).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        link.history.forEach { entry ->
            Text(reconciliationAuditLinkHistoryText(entry), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(color = palette.divider)
    }
    val reconciliation = audit.reconciliation
    if (reconciliation != null) {
        Text(RECONCILIATION_HISTORY_HEADER, style = MaterialTheme.typography.titleSmall)
        if (reconciliation.history.isEmpty()) {
            // F5: a reconciliation row whose history is empty says so — an empty section would
            // read as "nothing happened", and the row itself is real.
            Text(reconciliationAuditEmptyHistoryText(), style = MaterialTheme.typography.bodySmall)
        }
        reconciliation.history.forEach { entry ->
            Text(reconciliationAuditStatusHistoryText(entry), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The bind step (spec section 2.4): the explicit account decision, the warning, then the action. */
@Composable
private fun ReconciliationBindSection(
    state: P503AppState.ReconciliationDetail,
    onSelectBindTarget: (AccountId) -> Unit,
    onSelectBindEvidence: (String) -> Unit,
    onBind: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text("绑定到账户", style = MaterialTheme.typography.titleSmall)
    if (state.anchorPostingId != null) {
        // Entry A (F1): the target is the leg's own account and is never a user choice (spec
        // section 2.4); the carried account line is the exact account the queue was opened for.
        val targetLine = (state.returnTo as? ReconciliationBackTarget.Queue)?.state?.accountLine
        Text("目标账户：${targetLine ?: state.accountId?.value ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        when (val bindable = state.bindableEvidence) {
            null ->
                Text(
                    reconciliationBindableEvidenceFailureText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            is com.unifiedledger.application.P801BindableEvidenceResult.Failed ->
                Text(
                    reconciliationProposalFailureText(bindable.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            is com.unifiedledger.application.P801BindableEvidenceResult.Success ->
                if (bindable.rows.isEmpty()) {
                    // F1c: this is NOT 「该窗口内没有可用的导入行」 — it says exactly what is empty.
                    Text(reconciliationNoBindableEvidenceText(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    bindable.rows.forEach { evidence ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.bindEvidenceId == evidence.evidenceId,
                                onClick = { onSelectBindEvidence(evidence.evidenceId) },
                                enabled = !state.binding && !state.confirming,
                            )
                            Text(reconciliationBindableEvidenceText(evidence), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
        }
    } else {
        state.bindAccounts.forEach { choice ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.bindTarget == choice.accountId,
                    onClick = { onSelectBindTarget(choice.accountId) },
                    enabled = !state.binding && !state.confirming,
                )
                Text(choice.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    // The irreversibility warning precedes the action (spec section 2.4, verbatim).
    Text(RECONCILIATION_BIND_IRREVERSIBLE_WARNING, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))
    Button(onClick = onBind, enabled = reconciliationBindEnabled(state)) {
        Text(if (state.binding) "正在绑定…" else "绑定")
    }
}

/** One proposal: its evidence line, the frozen disposition, and every candidate of that proposal. */
@Composable
private fun ReconciliationProposalBlock(
    proposalIndex: Int,
    proposal: P801CandidateProposal,
    state: P503AppState.ReconciliationDetail,
    onSelectCandidate: (proposalIndex: Int, candidateIndex: Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            reconciliationProposalEvidenceText(proposal),
            style = MaterialTheme.typography.bodySmall,
            color = LocalP8Palette.current.onSurfaceVariant,
        )
        Text(
            reconciliationDispositionText(proposal.match.disposition, proposal.match.reason),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (proposal.match.disposition == P408MatchDisposition.UNRESOLVED) {
            // The matcher's own cause, quoted verbatim (spec section 2.4).
            Text(reconciliationUnresolvedCauseText(proposal.match.reason), style = MaterialTheme.typography.bodySmall)
        }
        proposal.match.candidates.forEachIndexed { candidateIndex, candidate ->
            val selected =
                state.selectedCandidate == P503AppState.ReconciliationSelection(proposalIndex, candidateIndex)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected,
                    onClick = { onSelectCandidate(proposalIndex, candidateIndex) },
                    enabled = !state.binding && !state.confirming,
                )
                Text(reconciliationCandidateText(candidate), style = MaterialTheme.typography.bodyMedium)
            }
            // F11: the audit belongs to the ANCHOR leg, so a sibling leg's candidate is judged
            // neutrally (a block reason is only rendered for the leg the audit actually read).
            reconciliationCandidateBlockReason(
                candidate,
                (state.audit as? P801ReconciledLegAuditResult.Success)?.audit,
                state.anchorPostingId,
            )?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            reconciliationCandidateEligibilityText(candidate.posting.eligibleRealAccount)?.let { eligibility ->
                Text(eligibility, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The four elements + the confirm action (spec section 2.5: two steps, then the call). */
@Composable
private fun ReconciliationConfirmBlock(
    state: P503AppState.ReconciliationDetail,
    palette: com.unifiedledger.ui.theme.P8Palette,
    onConfirm: () -> Unit,
) {
    val pair = reconciliationSelectedPair(state) ?: return
    val (proposal, candidate: P408MatchCandidate) = pair
    Spacer(Modifier.height(8.dp))
    Text(
        RECONCILIATION_ELEMENT_EXTERNAL_EVIDENCE + "：" + proposal.evidence.evidenceId,
        style = MaterialTheme.typography.bodyMedium,
    )
    val responsibility = P408EvidenceResponsibility.forDirectionOrNull(candidate.posting.direction)
    Text(
        RECONCILIATION_ELEMENT_RESPONSIBILITY + "：" + (responsibility?.let { duty -> reconciliationResponsibilityText(duty) } ?: "—（方向不可裁定）"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        RECONCILIATION_ELEMENT_MATCH_BASIS + "：" + reconciliationMatchBasisText(candidate.basis.fields.toList()),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        reconciliationNaturalDayDistanceText(candidate.basis.naturalDayDistance),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        RECONCILIATION_ELEMENT_HUMAN_DECISION + "：" + RECONCILIATION_HUMAN_DECISION_CONFIRM_MATCH,
        style = MaterialTheme.typography.bodyMedium,
        color = palette.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onConfirm, enabled = reconciliationConfirmEnabled(state)) {
        Text(if (state.confirming) "正在确认…" else "确认核对")
    }
}
