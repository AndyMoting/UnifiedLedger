package com.unifiedledger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidScaleTraversalTest {
    @Test
    fun anchoredOverlapPreservesMultiplicityAndOrder() {
        assertEquals(1, scaleWindowOffset(listOf("a", "b", "c", "d"), listOf("b", "c", "d"), 0, 3))
        assertEquals(1, scaleWindowOffset(listOf("a", "b", "b", "c"), listOf("b", "b", "c"), 0, 3))
        assertEquals(0, scaleWindowOffset(listOf("a", "a", "b"), listOf("a", "a"), 0, 0))
    }

    @Test
    fun skippedOrReorderedRowsAndWrongStartFail() {
        assertNull(scaleWindowOffset(listOf("a", "b", "c", "d"), listOf("c", "d"), 0, 2))
        assertNull(scaleWindowOffset(listOf("a", "b", "c", "d"), listOf("b", "d"), 0, 2))
        assertNull(scaleWindowOffset(listOf("a", "b", "c"), listOf("b", "a"), 0, 2))
        assertNull(scaleWindowOffset(listOf("a", "b", "c"), listOf("b", "c"), 0, 0))
    }

    @Test
    fun identicalNeighborsNeedAnAnchorNotLongestOverlap() {
        assertNull(scaleWindowOffset(listOf("a", "a", "a", "b"), listOf("a", "a"), 0, 3))
        assertEquals(2, scaleWindowOffset(listOf("a", "a", "a", "b"), listOf("a", "b"), 0, 3))
    }

    @Test
    fun stationaryAndRepeatedWindowNeverAdvanceObservedCount() {
        val original = listOf("a", "b", "c", "d")
        val window = listOf("b", "c")
        val offset = scaleWindowOffset(original, window, 1, 3)
        assertEquals(1, offset)
        assertEquals(3, offset!! + window.size)
        assertNull(scaleWindowOffset(original, emptyList(), 0, 3))
    }
}
