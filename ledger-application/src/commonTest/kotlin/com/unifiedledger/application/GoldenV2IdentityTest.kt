package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Python generator that produced the frozen expected outputs refuses malformed identity
 * components outright. If this side accepted them it would mint identities the contract generator
 * could never emit, so the same inputs must fail here too.
 */
class GoldenV2IdentityTest {
    private val root = goldenV2RootId("RG-05", "$.manual_path", "request-rg05-manual")

    @Test
    fun rejectsEmptyIdentityComponents() {
        assertFailsWith<GoldenV2IdentityException> { goldenV2RootId("", "$.manual_path", "occurrence") }
        assertFailsWith<GoldenV2IdentityException> { goldenV2RootId("RG-05", "$.manual_path", "") }
        assertFailsWith<GoldenV2IdentityException> { goldenV2MigrationId("RG-05", "", "confirmation", "$.manual_path", "occurrence") }
        assertFailsWith<GoldenV2IdentityException> { goldenV2MigrationId("RG-05", root, "", "$.manual_path", "occurrence") }
    }

    @Test
    fun rejectsControlCharactersInIdentityComponents() {
        assertFailsWith<GoldenV2IdentityException> { goldenV2RootId("RG-05", "$.manual_path", "occur\nrence") }
        assertFailsWith<GoldenV2IdentityException> { goldenV2RootId("RG\u0000-05", "$.manual_path", "occurrence") }
        assertFailsWith<GoldenV2IdentityException> { goldenV2MigrationId("RG-05", root, "confirm\u007fation", "$.manual_path", "occurrence") }
    }

    @Test
    fun acceptsWellFormedIdentityComponents() {
        assertEquals(36, goldenV2MigrationId("RG-05", root, "confirmation", "$.manual_path", "occurrence").length)
        // A space is not a control character and must stay legal in every component.
        assertEquals(36, goldenV2RootId("RG 05", "$.manual_path", "occur rence").length)
    }

    @Test
    fun rejectsSourceLocatorsThatAreNotNormalizedPaths() {
        listOf(
            "manual_path",
            "$.manual_path[0]",
            "$.manual_path.",
            "$/manual_path",
            "$.manual_path\n",
        ).forEach { locator ->
            assertFailsWith<GoldenV2IdentityException>("locator must be rejected: $locator") {
                goldenV2RootId("RG-05", locator, "occurrence")
            }
        }
    }

    @Test
    fun acceptsNormalizedSourceLocators() {
        listOf(
            "$",
            "$.manual_path",
            "$.import_path.ordered_operations[*].expected.candidate_status",
            "$.invalid_manual_inputs[*].id",
        ).forEach { locator ->
            val id = goldenV2RootId("RG-05", locator, "occurrence")
            assertEquals(36, id.length, "locator must be accepted: $locator")
        }
    }
}
