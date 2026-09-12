package com.unifiedledger.application

import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.EntryFoundationViolation

/**
 * P7-02.A S-4: frozen note length limit in Unicode code points. The draft note is optional
 * (empty allowed); a normalized note above this limit is a typed rejection with zero formal
 * writes.
 */
const val ENTRY_NOTE_MAX_CODE_POINTS: Int = 200

/**
 * Pure note validation shared by the expense and income commit transaction factories. Counting
 * is by code point (a surrogate pair counts once) so the limit is not byte- or UTF-16-length
 * dependent.
 */
fun validateEntryNote(note: String): DomainViolation? =
    if (note.codePointCount() > ENTRY_NOTE_MAX_CODE_POINTS) {
        EntryFoundationViolation.NoteTooLong
    } else {
        null
    }

/**
 * P7-02.A §5: stable failure codes owned by this batch. The literal [code] is the frozen value
 * consumers compare; enum constant names are only an internal spelling and messages are never
 * compared (same discipline as [CatalogFailureCode]).
 */
enum class EntryFoundationFailureCode(
    val code: String,
) {
    NOTE_TOO_LONG("NoteTooLong"),
    ENTRY_TYPE_NOT_SUPPORTED("EntryTypeNotSupported"),
    ;

    companion object {
        /** Maps one of this batch's domain tokens to its stable code; other tokens stay unmapped. */
        fun of(violation: DomainViolation): EntryFoundationFailureCode? =
            when (violation) {
                EntryFoundationViolation.NoteTooLong -> NOTE_TOO_LONG
                EntryFoundationViolation.EntryTypeNotSupported -> ENTRY_TYPE_NOT_SUPPORTED
                else -> null
            }
    }
}

/** Unicode code-point count that treats a high+low surrogate pair as one code point. */
internal fun String.codePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val char = this[index]
        index += if (char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
        count += 1
    }
    return count
}
