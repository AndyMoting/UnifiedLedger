package com.unifiedledger.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class StrictJsonPreflightReason { RESOURCE_LIMIT, DUPLICATE_KEY, MALFORMED_JSON, OBJECT_ROOT_REQUIRED }

internal data class StrictJsonPreflightIssue(
    val path: String,
    val reason: StrictJsonPreflightReason,
)

internal fun strictJsonPreflight(
    raw: String,
    duplicateKeyPath: Boolean,
): StrictJsonPreflightIssue? {
    if (raw.length > 1_048_576 || raw.encodeToByteArray().size > 1_048_576) {
        return StrictJsonPreflightIssue("$", StrictJsonPreflightReason.RESOURCE_LIMIT)
    }
    StrictScanner(raw, duplicateKeyPath).scan()?.let { return it }
    val root =
        try {
            Json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return StrictJsonPreflightIssue("$", StrictJsonPreflightReason.MALFORMED_JSON)
        }
    return if (root is JsonObject) null else StrictJsonPreflightIssue("$", StrictJsonPreflightReason.OBJECT_ROOT_REQUIRED)
}

private class StrictScanner(
    private val text: String,
    private val includeDuplicateKey: Boolean,
) {
    private var index = 0

    fun scan(): StrictJsonPreflightIssue? =
        try {
            skip()
            value("$", 0)
            skip()
            if (index != text.length) throw Syntax()
            null
        } catch (failure: Duplicate) {
            StrictJsonPreflightIssue(failure.path, StrictJsonPreflightReason.DUPLICATE_KEY)
        } catch (failure: Limit) {
            StrictJsonPreflightIssue(failure.path, StrictJsonPreflightReason.RESOURCE_LIMIT)
        } catch (_: Syntax) {
            null
        }

    private fun value(
        path: String,
        depth: Int,
    ) {
        skip()
        when (text.getOrNull(index)) {
            '{' -> obj(path, depth + 1)
            '[' -> array(path, depth + 1)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> throw Syntax()
        }
    }

    private fun obj(
        path: String,
        depth: Int,
    ) {
        depth(path, depth)
        index++
        val keys = mutableSetOf<String>()
        skip()
        if (take('}')) return
        while (true) {
            skip()
            val key = string()
            if (!keys.add(key)) throw Duplicate(if (includeDuplicateKey) "$path.$key" else path)
            skip()
            expect(':')
            value("$path.$key", depth)
            skip()
            if (take('}')) return
            expect(',')
        }
    }

    private fun array(
        path: String,
        depth: Int,
    ) {
        depth(path, depth)
        index++
        skip()
        if (take(']')) return
        var item = 0
        while (true) {
            value("$path[$item]", depth)
            item++
            skip()
            if (take(']')) return
            expect(',')
        }
    }

    private fun depth(
        path: String,
        depth: Int,
    ) {
        if (depth > 64) throw Limit(path)
    }

    private fun string(): String {
        expect('"')
        val start = index
        var escaped = false
        while (index < text.length) {
            val c = text[index++]
            if (c == '"' && !escaped) {
                return try {
                    Json.parseToJsonElement("\"${text.substring(start, index - 1)}\"").jsonPrimitive.content
                } catch (_: Exception) {
                    throw Syntax()
                }
            }
            escaped = c == '\\' && !escaped
        }
        throw Syntax()
    }

    private fun literal(value: String) {
        if (!text.startsWith(value, index)) throw Syntax()
        index += value.length
    }

    private fun number() {
        if (text.getOrNull(index) == '-') index++
        if (text.getOrNull(index) == '0') {
            index++
        } else {
            if (text.getOrNull(index) !in '1'..'9') throw Syntax()
            while (text.getOrNull(index)?.isDigit() == true) index++
        }
        if (take('.')) {
            if (text.getOrNull(index)?.isDigit() != true) throw Syntax()
            while (text.getOrNull(index)?.isDigit() == true) index++
        }
        if (text.getOrNull(index) in listOf('e', 'E')) {
            index++
            if (text.getOrNull(index) in listOf('+', '-')) index++
            if (text.getOrNull(index)?.isDigit() != true) throw Syntax()
            while (text.getOrNull(index)?.isDigit() == true) index++
        }
    }

    private fun skip() {
        while (text.getOrNull(index)?.isWhitespace() == true) index++
    }

    private fun expect(c: Char) {
        if (!take(c)) throw Syntax()
    }

    private fun take(c: Char) =
        if (text.getOrNull(index) == c) {
            index++
            true
        } else {
            false
        }

    private class Duplicate(
        val path: String,
    ) : RuntimeException()

    private class Limit(
        val path: String,
    ) : RuntimeException()

    private class Syntax : RuntimeException()
}
