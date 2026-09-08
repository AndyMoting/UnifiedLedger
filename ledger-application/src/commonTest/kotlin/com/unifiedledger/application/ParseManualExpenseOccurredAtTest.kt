package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * D-138: 发生时间宽容解析器测试（规格 §2.2 全格式矩阵与拒绝向量、§3.1 冻结六向量矩阵、
 * DST 空档冻结日期与邻近接受向量、固定时钟年补齐、ISO superset）。DST 向量与选择器侧
 * OccurredAtPickerTest 一致（同一 tzdb 数据源，两侧实现的结构纽带，规格 §2.1 实现落点）。
 */
class ParseManualExpenseOccurredAtTest {
    // 固定时钟 2026-09-08T12:00:00Z 的 Asia/Shanghai 本地年为 2026（规格 §2.2 (c) 示例同源）。
    private val clock = FixedLedgerClock(Instant.parse("2026-09-08T12:00:00Z"))
    private val parser = ParseManualExpenseOccurredAt()

    @Test
    fun frozenFormatMatrixParsesToTheExpectedInstants() {
        val vectors =
            mapOf(
                // (a) ISO 8601 instant，秒可选、仅 Z 结尾，不经墙钟换算。
                "2026-09-15T00:30:00Z" to "2026-09-15T00:30:00Z",
                "2026-09-15T00:30Z" to "2026-09-15T00:30:00Z",
                // (b) 空格或 T 分隔的 Asia/Shanghai 墙钟，单位数小时与秒可选。
                "2026-09-15 08:30" to "2026-09-15T00:30:00Z",
                "2026-09-15 08:30:15" to "2026-09-15T00:30:15Z",
                "2026-09-15T08:30" to "2026-09-15T00:30:00Z",
                "2026-09-15 8:30" to "2026-09-15T00:30:00Z",
                // (c) 省年格式，年由固定时钟的 Asia/Shanghai 本地年补齐。
                "09-15 20:00" to "2026-09-15T12:00:00Z",
                "09-15 20:00:05" to "2026-09-15T12:00:05Z",
                "09-15" to "2026-09-14T16:00:00Z",
                // (d) 仅日期 → 本地 00:00 开始时刻。
                "2026-09-15" to "2026-09-14T16:00:00Z",
            )
        for ((text, expected) in vectors) {
            val result = assertIs<ParseManualExpenseOccurredAt.Result.Valid>(parser.parse(text, clock))
            assertEquals(Instant.parse(expected), result.instant, "for input $text")
        }
    }

    @Test
    fun frozenSixVectorMatrixFromTheReviewClosure() {
        // 闰年接受。规格 §3.1 行的期望值 `2028-02-28T00:30:00Z` 与冻结换算机制（§2.1 固定
        // Asia/Shanghai +08:00、§2.2 (b) 示例 08:30 → 当日 00:30Z）相差一日，按机制应为
        // `2028-02-29T00:30:00Z`（与选择器侧换算同构）；本向量以机制为准（待主代理对账）。
        assertEquals(Instant.parse("2028-02-29T00:30:00Z"), valid("2028-02-29 08:30"))
        // 平年 2-29 拒绝。
        assertInvalid("2026-02-29", OccurredAtFormatError.INVALID_FORMAT)
        // 边界时刻接受。
        assertEquals(Instant.parse("2026-09-14T16:00:00Z"), valid("2026-09-15 00:00"))
        // 两端空白 trim 规则。
        assertEquals(Instant.parse("2026-09-15T00:30:00Z"), valid(" 2026-09-15 08:30 "))
        // (b) 的 T 分隔 + 秒。
        assertEquals(Instant.parse("2026-09-15T00:30:15Z"), valid("2026-09-15T08:30:15"))
        // 跨年钉：固定时钟 2026-12-31 23:59（Asia/Shanghai），补年取本地年 2026。
        val yearEndClock = FixedLedgerClock(Instant.parse("2026-12-31T15:59:00Z"))
        assertEquals(Instant.parse("2026-01-01T16:00:00Z"), valid("01-02", yearEndClock))
    }

    @Test
    fun everyFrozenRejectionVectorIsInvalid() {
        val vectors =
            listOf(
                // 相对/自然语言表达（用户裁决：不支持「昨天 20:00」这类相对表达）。
                "昨天 20:00",
                "明天 09:00",
                "上周三 12:00",
                "上周",
                // 其他 locale 格式。
                "2026/09/15 08:30",
                "09-15-2026",
                "8:30 PM",
                "2026年9月15日",
                "15/09/2026",
                // 月/日未两位填充（零填充冻结；单位数小时仅限 (b) 墙钟格式）。
                "2026-9-5 8:30",
                "2026-9-15",
                "2026-09-5",
                "09-5",
                // 格式 (a) 小时必须两位。
                "2026-09-15T8:30Z",
                // (b) 与 (a) 混写 / 仅接受大写 Z。
                "2026-09-15 08:30Z",
                "2026-09-15T00:30:00z",
                // 小数秒/非 Z 偏移不在格式集内。
                "2026-09-15T00:30:00.5Z",
                "2026-09-15T00:30:00+08:00",
                // 越界组件。
                "2026-09-15 25:00",
                "2026-09-15 08:61",
                "2026-13-15",
                "2026-02-30",
                "2026-02-29",
                // 内部空白：空格分隔形式仅允许单个空格。
                "2026-09-15  08:30",
                "2026-09-15\t08:30",
                // 空串与纯空白。
                "",
                "   ",
            )
        for (text in vectors) {
            assertInvalid(text, OccurredAtFormatError.INVALID_FORMAT)
        }
    }

    @Test
    fun historicalDstGapTimesFailClosed() {
        // 冻结日期与选择器侧 OccurredAtPickerTest:33-36 一致（规格 §2.2 拒绝向量）。
        assertInvalid("1986-05-04 02:30", OccurredAtFormatError.DST_GAP)
        assertInvalid("1991-04-14 02:30", OccurredAtFormatError.DST_GAP)
    }

    @Test
    fun timesAdjacentToHistoricalDstGapsAreAccepted() {
        val vectors =
            mapOf(
                // 邻近接受向量同日 01:30/03:30（选择器侧先例 OccurredAtPickerTest:40-48）。
                "1986-05-04 01:30" to "1986-05-03T17:30:00Z",
                "1986-05-04 03:30" to "1986-05-03T18:30:00Z",
                "1991-04-14 01:30" to "1991-04-13T17:30:00Z",
                "1991-04-14 03:30" to "1991-04-13T18:30:00Z",
            )
        for ((text, expected) in vectors) {
            assertEquals(Instant.parse(expected), valid(text), "for input $text")
        }
    }

    @Test
    fun yearCompletionUsesTheShanghaiLocalYearOfTheClock() {
        // 2026-12-31T16:00:00Z 的 UTC 年为 2026，但在 Asia/Shanghai 已是 2027-01-01：
        // 补年取 Asia/Shanghai 本地年（规格 §2.1），故补为 2027。
        val newYearClock = FixedLedgerClock(Instant.parse("2026-12-31T16:00:00Z"))
        assertEquals(Instant.parse("2027-01-01T16:00:00Z"), valid("01-02", newYearClock))
    }

    private fun valid(
        text: String,
        clock: LedgerClock = this.clock,
    ): Instant {
        val result = assertIs<ParseManualExpenseOccurredAt.Result.Valid>(parser.parse(text, clock))
        return result.instant
    }

    private fun assertInvalid(
        text: String,
        expected: OccurredAtFormatError,
        clock: LedgerClock = this.clock,
    ) {
        val result = assertIs<ParseManualExpenseOccurredAt.Result.Invalid>(parser.parse(text, clock))
        assertEquals(expected, result.reason, "for input $text")
    }
}
