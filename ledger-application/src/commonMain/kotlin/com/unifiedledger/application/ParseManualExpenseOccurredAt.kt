package com.unifiedledger.application

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 发生时间手工输入宽容解析器（D-138 规格 §2.1/§2.2）。形状镜像 [ParseManualExpenseAmount]：
 * 只 trim 两端 ASCII 空白（space/tab/CR/LF）→ trim 后空串拒绝 → 结构解析，sealed [Result]
 * 显式返回结果。
 *
 * 接受四种冻结绝对格式：(a) ISO 8601 instant `YYYY-MM-DDTHH:mm(:ss)?Z`（直接解析为
 * instant，不经过墙钟换算，无 DST 空档概念）；(b) `YYYY-MM-DD[ 空格或 T]HH:mm(:ss)`
 *（Asia/Shanghai 墙钟，秒可选，允许单位数小时）；(c) 省年 `MM-DD HH:mm(:ss)`/`MM-DD`，年由
 * 注入的 [LedgerClock] 在 Asia/Shanghai 墙钟帧内的本地年补齐（文档化选择，规格 §2.1）；
 * (d) `YYYY-MM-DD`（仅日期，取本地 00:00 开始时刻）。月/日必须两位零填充，年必须四位；
 * (a) 小时必须两位且仅 `Z` 结尾；小数秒与非 `Z` 偏移不在任何格式内。相对/自然语言表达
 *（用户裁决原文「不要支持"昨天 20:00"这种相对表达」）与一切其他 locale 格式一律
 * [OccurredAtFormatError.INVALID_FORMAT]。
 *
 * 墙钟格式按固定 Asia/Shanghai 时区执行与选择器同款 round-trip 一致性检查（规格 §2.1）：
 * 构造 LocalDateTime → `toInstant` → 回推 `toLocalDateTime` 逐字段相等才接受；本地时刻
 * 不存在（历史 DST 空档）→ [OccurredAtFormatError.DST_GAP]（fail-closed，绝不猜测、绝不
 * 静默偏移）。该换算规则在 app-ui（OccurredAtPicker.kt，选择器零改动）与本文件各有一份
 * 实现——模块边界所致故意重复，两侧以冻结 DST 日期向量为一致性纽带（规格 §2.1 实现落点）。
 */
enum class OccurredAtFormatError {
    INVALID_FORMAT,
    DST_GAP,
}

class ParseManualExpenseOccurredAt {
    sealed interface Result {
        data class Valid(
            val instant: Instant,
        ) : Result

        data class Invalid(
            val reason: OccurredAtFormatError,
        ) : Result
    }

    fun parse(
        text: String,
        clock: LedgerClock,
    ): Result {
        val trimmed = text.trim(::isAsciiWhitespace)
        if (trimmed.isEmpty()) {
            return Result.Invalid(OccurredAtFormatError.INVALID_FORMAT)
        }
        // 结构解析：四种格式互斥；任一不匹配（含双空格等内部空白、相对表达与一切未冻结
        // 格式）一律 INVALID_FORMAT。格式 (a) 直接构造 instant，不经过墙钟换算。
        val instantMatch = ISO_INSTANT_FORMAT.matchEntire(trimmed)
        if (instantMatch != null) {
            val local =
                buildLocalDateTime(
                    year = instantMatch.groupValues[1].toInt(),
                    month = instantMatch.groupValues[2].toInt(),
                    day = instantMatch.groupValues[3].toInt(),
                    hour = instantMatch.groupValues[4].toInt(),
                    minute = instantMatch.groupValues[5].toInt(),
                    second = instantMatch.groupValues[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
                ) ?: return Result.Invalid(OccurredAtFormatError.INVALID_FORMAT)
            return Result.Valid(local.toInstant(TimeZone.UTC))
        }
        val wallClockMatch = WALL_CLOCK_FORMAT.matchEntire(trimmed)
        if (wallClockMatch != null) {
            val local =
                buildLocalDateTime(
                    year = wallClockMatch.groupValues[1].toInt(),
                    month = wallClockMatch.groupValues[2].toInt(),
                    day = wallClockMatch.groupValues[3].toInt(),
                    hour = wallClockMatch.groupValues[4].toInt(),
                    minute = wallClockMatch.groupValues[5].toInt(),
                    second = wallClockMatch.groupValues[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
                ) ?: return Result.Invalid(OccurredAtFormatError.INVALID_FORMAT)
            return toWallClockRoundTrip(local)
        }
        val yearlessMatch = YEARLESS_FORMAT.matchEntire(trimmed)
        if (yearlessMatch != null) {
            val completionYear = clock.now().toLocalDateTime(OCCURRED_AT_TIME_ZONE).year
            val local =
                buildLocalDateTime(
                    year = completionYear,
                    month = yearlessMatch.groupValues[1].toInt(),
                    day = yearlessMatch.groupValues[2].toInt(),
                    hour = yearlessMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
                    minute = yearlessMatch.groupValues[4].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
                    second = yearlessMatch.groupValues[5].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
                ) ?: return Result.Invalid(OccurredAtFormatError.INVALID_FORMAT)
            return toWallClockRoundTrip(local)
        }
        val dateOnlyMatch = DATE_ONLY_FORMAT.matchEntire(trimmed)
        if (dateOnlyMatch != null) {
            val local =
                buildLocalDateTime(
                    year = dateOnlyMatch.groupValues[1].toInt(),
                    month = dateOnlyMatch.groupValues[2].toInt(),
                    day = dateOnlyMatch.groupValues[3].toInt(),
                    hour = 0,
                    minute = 0,
                    second = 0,
                ) ?: return Result.Invalid(OccurredAtFormatError.INVALID_FORMAT)
            return toWallClockRoundTrip(local)
        }
        return Result.Invalid(OccurredAtFormatError.INVALID_FORMAT)
    }

    private fun toWallClockRoundTrip(local: LocalDateTime): Result {
        val instant = local.toInstant(OCCURRED_AT_TIME_ZONE)
        return if (instant.toLocalDateTime(OCCURRED_AT_TIME_ZONE) == local) {
            Result.Valid(instant)
        } else {
            Result.Invalid(OccurredAtFormatError.DST_GAP)
        }
    }

    private fun buildLocalDateTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): LocalDateTime? {
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }
        if (day > daysInMonth(month, year)) {
            return null
        }
        return runCatching { LocalDateTime(year, month, day, hour, minute, second) }.getOrNull()
    }

    private fun daysInMonth(
        month: Int,
        year: Int,
    ): Int =
        when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            else -> if (isLeapYear(year)) 29 else 28
        }

    private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private companion object {
        val OCCURRED_AT_TIME_ZONE: TimeZone = TimeZone.of("Asia/Shanghai")

        // (a) `YYYY-MM-DDTHH:mm(:ss)?Z`：仅 Z 结尾，小时必须两位，无小数秒/偏移。
        val ISO_INSTANT_FORMAT = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?Z$""")

        // (b) `YYYY-MM-DD[ 或 T]HH:mm(:ss)`：单个空格或 T 分隔，单位数小时允许，不接受 Z。
        val WALL_CLOCK_FORMAT = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?$""")

        // (c) `MM-DD HH:mm(:ss)` 或 `MM-DD`：月/日两位、小时两位（单位数小时仅限 (b)）。
        val YEARLESS_FORMAT = Regex("""^(\d{2})-(\d{2})(?: (\d{2}):(\d{2})(?::(\d{2}))?)?$""")

        // (d) `YYYY-MM-DD`：仅日期 → 本地 00:00。
        val DATE_ONLY_FORMAT = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

        fun isAsciiWhitespace(character: Char): Boolean = character == ' ' || character == '\t' || character == '\r' || character == '\n'
    }
}
