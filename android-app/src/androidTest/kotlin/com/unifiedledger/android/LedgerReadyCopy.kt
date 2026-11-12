package com.unifiedledger.android

/**
 * The home-tab copy that proves the ledger opened and the first frame rendered. Mirrors
 * READY_MARKERS in tools/python/android_scale/result.py; the Python guard compares the two
 * lists, so a home-copy edit updates both or reds.
 */
internal object LedgerReadyCopy {
    val PREFIXES = listOf("账本为空", "流水 · 共 ", "当前交易（最近 ")

    fun matches(text: String): Boolean = PREFIXES.any(text::startsWith)
}
