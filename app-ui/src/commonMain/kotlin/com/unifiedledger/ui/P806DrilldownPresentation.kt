package com.unifiedledger.ui

import com.unifiedledger.domain.CategoryId

/*
 * P8-06 batch 3 (D-362 1/2; spec sections 3.7/3.9/3.10.4): the report drilldown's pure presentation
 * decisions. Everything here is a pure function over the report slot or the drilldown state, so the
 * JVM tests hold the entry eligibility, the typed target construction, the six payload faces and the
 * header label without a Compose runtime.
 */

/**
 * Whether the report region offers ANY drilldown entry (D-362 1; spec section 3.9). Three
 * conditions, all necessary: the graph wires the drilldown read ([wired]), the slot is the shared
 * eligibility seam's CURRENT result ([p806ReportSlotIsEligible]) and the landed snapshot actually
 * resolved a window (`landed.period.window != null`). An empty ledger or an `ALL` report without a
 * window renders no entry at all — never a zero and never a dead button. The category entries
 * additionally require their row to exist, which the READY face's category sections already encode.
 */
internal fun p806ReportDrilldownEntryVisible(
    slot: P806ReportRegionState?,
    wired: Boolean,
): Boolean = wired && slot != null && p806ReportSlotIsEligible(slot) && slot.landed?.period?.window != null

/**
 * The period target of [slot] (「本期全部明细」), or `null` when the slot has no current window.
 * The window is the resolved report geometry of the last accepted snapshot — the SAME window the
 * report period read used, never a second resolution.
 */
internal fun p806DrilldownPeriodTarget(slot: P806ReportRegionState?): ReportDrilldownTarget.Period? {
    val window =
        slot
            ?.takeIf { candidate -> p806ReportSlotIsEligible(candidate) }
            ?.landed
            ?.period
            ?.window
            ?: return null
    return ReportDrilldownTarget.Period(window)
}

/**
 * The category target of one row of [slot] (a level-1 「全部明细」 or a level-2 row's own entry), or
 * `null` when the slot has no current window. [categoryId] is the catalog id the row carries (the
 * application resolves the subtree; a caller-supplied category NAME is never the predicate) and
 * [categoryName] is only the header label captured at open time.
 */
internal fun p806DrilldownCategoryTarget(
    slot: P806ReportRegionState?,
    categoryId: CategoryId,
    categoryName: String,
): ReportDrilldownTarget.Category? {
    val window =
        slot
            ?.takeIf { candidate -> p806ReportSlotIsEligible(candidate) }
            ?.landed
            ?.period
            ?.window
            ?: return null
    return ReportDrilldownTarget.Category(window = window, categoryId = categoryId, categoryName = categoryName)
}

/**
 * The six distinguishable faces of one drilldown page (D-343 section 3.7.7), in the frozen priority
 * order: [UNWIRED] first (no capability claim at all), then [NOT_LOADED] (nothing requested, no
 * failure), then an in-flight [LOADING], then a typed [FAILED], then the two honest successes —
 * [READY_EMPTY] (`totalCount == 0` with no rows) and [READY_NON_EMPTY].
 *
 * The reducer never produces `totalCount == 0` with rows; if a synthetic state does, the last branch
 * presents it as [READY_NON_EMPTY] for diagnosis and never claims the empty success.
 */
internal enum class P806DrilldownFace {
    UNWIRED,
    NOT_LOADED,
    LOADING,
    FAILED,
    READY_EMPTY,
    READY_NON_EMPTY,
}

/** The face decision over the drilldown's explicit state (see [P806DrilldownFace]). */
internal fun p806DrilldownFace(state: P503AppState.ReportDrilldown): P806DrilldownFace =
    when {
        state.unwired -> P806DrilldownFace.UNWIRED
        state.totalCount == null && !state.rowsLoading && state.failure == null -> P806DrilldownFace.NOT_LOADED
        state.rowsLoading -> P806DrilldownFace.LOADING
        state.failure != null -> P806DrilldownFace.FAILED
        state.totalCount == 0 && state.loadedRows.isEmpty() -> P806DrilldownFace.READY_EMPTY
        else -> P806DrilldownFace.READY_NON_EMPTY
    }

/**
 * The header label of the open drilldown: the typed target's own name (「本期全部明细」 for the
 * period window, the captured category name for a category subtree), plus ` · 读取时点 <asOf>` when
 * the last accepted landing captured a read instant. A clock-less read carries no time suffix
 * instead of a fabricated one.
 */
internal fun p806DrilldownHeaderLabel(state: P503AppState.ReportDrilldown): String {
    val base =
        when (val target = state.target) {
            is ReportDrilldownTarget.Period -> "本期全部明细"
            is ReportDrilldownTarget.Category -> target.categoryName
        }
    val asOf = state.landedAsOf ?: return base
    return "$base · 读取时点 $asOf"
}
