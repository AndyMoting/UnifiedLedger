package com.unifiedledger.application

import com.unifiedledger.domain.EntryFoundationViolation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P7-02.A S-4/§5: the note limit is 200 code points, empty is allowed, and the stable failure
 * code is `NoteTooLong`.
 */
class EntryNoteValidationTest {
    @Test
    fun `empty and prefix notes are accepted`() {
        assertNull(validateEntryNote(""))
        assertNull(validateEntryNote("a".repeat(ENTRY_NOTE_MAX_CODE_POINTS)))
    }

    @Test
    fun `over-limit note is rejected with the stable token`() {
        assertEquals(
            EntryFoundationViolation.NoteTooLong,
            validateEntryNote("a".repeat(ENTRY_NOTE_MAX_CODE_POINTS + 1)),
        )
    }

    @Test
    fun `limit counts code points not utf-16 units`() {
        // 200 astral code points are 400 UTF-16 units but exactly at the code-point limit.
        val astral = "\uD83D\uDE00".repeat(ENTRY_NOTE_MAX_CODE_POINTS)
        assertNull(validateEntryNote(astral))
        assertEquals(
            EntryFoundationViolation.NoteTooLong,
            validateEntryNote(astral + "\uD83D\uDE00"),
        )
    }

    @Test
    fun `failure code mapping is stable`() {
        assertEquals("NoteTooLong", EntryFoundationFailureCode.NOTE_TOO_LONG.code)
        assertEquals("EntryTypeNotSupported", EntryFoundationFailureCode.ENTRY_TYPE_NOT_SUPPORTED.code)
        assertEquals(
            EntryFoundationFailureCode.NOTE_TOO_LONG,
            EntryFoundationFailureCode.of(EntryFoundationViolation.NoteTooLong),
        )
        assertEquals(
            EntryFoundationFailureCode.ENTRY_TYPE_NOT_SUPPORTED,
            EntryFoundationFailureCode.of(EntryFoundationViolation.EntryTypeNotSupported),
        )
        assertNull(EntryFoundationFailureCode.of(com.unifiedledger.domain.OrdinaryExpenseViolation.AmountMustBePositive))
    }
}
