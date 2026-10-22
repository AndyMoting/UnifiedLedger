package com.unifiedledger.android

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SOURCE-LEVEL PIN for the catalog-command -> loaded-filter re-request seam (D-238 vector A07,
 * spec section 5.5 row 1). This is NOT behavioural evidence: it proves the wiring exists and has
 * the expected shape — the re-request call sits inside the catalog-command refresh function body,
 * and that function is invoked only from the determinate-success (`Accepted || NoChange`) gate of a
 * tag/merchant catalog command. It does not execute the composable and therefore does not prove
 * that a rename re-requests the loaded filter page at runtime.
 *
 * Why the pin lives in this module: the refresh function is a local function of the `@Composable`
 * `P503App`, which a plain JVM test cannot construct, and `app-ui` declares an Android target, so
 * its `commonTest` cannot source-scan the file (the disclosure registered at
 * `P503App.kt:4838-4844`). `android-app/src/test` already source-scans `P503App.kt` (the
 * `LedgerWidgetSnapshotReaderTest` / `AppShortcutsTest` precedent).
 *
 * The comparisons are line-based on purpose: the file is read, newline-normalised, split into
 * lines, and each line is `trim()`ed before an exact whole-line comparison, so the verdict is
 * identical on the CRLF working tree and on the LF blob. A multi-line literal would not be, and a
 * bare `Regex(...).findAll(...).count()` on the call literal would also match the function's own
 * declaration line.
 */
class P708TagMerchantFilterReRequestWiringTest {

    @Test
    fun theLoadedFilterReRequestLivesInsideTheRefreshFunctionBody() {
        val lines = trimmedProductionLines()
        val definition = lines.indexOfFirst { it == REFRESH_FUNCTION_DEFINITION }
        assertTrue(
            definition >= 0,
            "the catalog-command refresh function must still be declared in the composition root",
        )
        val body = functionBodySpan(lines, definition)
        assertTrue(
            body.any { it == FILTER_RE_REQUEST_CALL },
            "the loaded filter page must be re-requested with replace inside the refresh function body",
        )
    }

    @Test
    fun theRefreshFunctionIsInvokedFromExactlyTheTwoCatalogCommandLandings() {
        val callSites = trimmedProductionLines().withIndex().filter { (_, line) -> line == REFRESH_FUNCTION_CALL }
        assertEquals(
            2,
            callSites.size,
            "the refresh must be invoked from the catalog command landings only",
        )
    }

    @Test
    fun everyRefreshCallSitsInsideTheAcceptedOrNoChangeGate() {
        val lines = trimmedProductionLines()
        val callSites = lines.withIndex().filter { (_, line) -> line == REFRESH_FUNCTION_CALL }
        assertTrue(
            callSites.isNotEmpty(),
            "there must be at least one landing call to check",
        )
        for ((index, _) in callSites) {
            assertTrue(
                index >= GATE_SEQUENCE.size,
                "a landing call must have the determinate-success gate directly above it",
            )
            assertEquals(
                GATE_SEQUENCE,
                lines.subList(index - GATE_SEQUENCE.size, index),
                "each landing call must sit directly inside the Accepted || NoChange gate",
            )
        }
    }

    /**
     * Reads the composition root and returns its lines with `trim()` applied, after normalising
     * both CRLF and lone CR to LF so the comparison is checkout-independent.
     */
    private fun trimmedProductionLines(): List<String> =
        repositoryFile(PRODUCTION_ROOT)
            .toFile()
            .readText()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .map { it.trim() }

    /**
     * Returns the lines of the function body declared at [definitionIndex], closed by brace
     * balance (`{` adds one, `}` removes one) from the declaration line onward. A never-balancing
     * body is a hard error rather than a silently truncated span.
     */
    private fun functionBodySpan(lines: List<String>, definitionIndex: Int): List<String> {
        var depth = 0
        var index = definitionIndex
        while (index < lines.size) {
            val line = lines[index]
            depth += line.count { it == '{' } - line.count { it == '}' }
            index++
            if (depth == 0) return lines.subList(definitionIndex, index)
        }
        error("the refresh function body's braces never balance")
    }

    /**
     * Resolves a path relative to the repository root by walking up from the test working directory
     * to the settings file (the `AndroidRestoreWiringTest` precedent).
     */
    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private companion object {
        private const val PRODUCTION_ROOT = "app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt"

        private const val REFRESH_FUNCTION_DEFINITION = "fun refreshTagMerchantSurfacesAfterCommand() {"

        private const val REFRESH_FUNCTION_CALL = "refreshTagMerchantSurfacesAfterCommand()"

        private const val FILTER_RE_REQUEST_CALL = "requestTagMerchantFilterPage(current, replace = true, cursor = null)"

        private val GATE_SEQUENCE = listOf(
            "if (result is TagMerchantCommandResult.Accepted ||",
            "result is TagMerchantCommandResult.NoChange",
            ") {",
        )
    }
}
