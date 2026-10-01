package com.unifiedledger.android

/** Unique contiguous overlap, preserving repeated occurrences; missing rows are never inferred. */
internal fun scaleWindowOffset(expected: List<String>, window: List<String>, previousStart: Int, observed: Int): Int? {
    if (window.isEmpty()) return null
    val candidates = if (observed == 0) listOf(0) else (previousStart until observed).toList()
    return candidates.filter { start -> start + window.size <= expected.size && expected.subList(start, start + window.size) == window }.singleOrNull()
}
