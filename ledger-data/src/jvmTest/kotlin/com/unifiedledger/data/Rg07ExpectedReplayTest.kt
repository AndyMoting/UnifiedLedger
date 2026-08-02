package com.unifiedledger.data

import com.unifiedledger.application.replayRg07Fixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Rg07ExpectedReplayTest {
    @Test
    fun checkedInExpectedIsRequiredAndFullyCounted() {
        val path = repositoryFile("docs/migrations/golden-v2/rg-07-expected.json")
        assertTrue(Files.exists(path), "checked-in RG-07 expected artifact is required")
        val summary = replayRg07Fixture(Files.readString(path))
        assertEquals(23, summary.rootCount)
        assertEquals(72, summary.stateCount)
        assertEquals(49, summary.operations.size)
        assertEquals(16, summary.accepted)
        assertEquals(12, summary.noChange)
        assertEquals(21, summary.rejected)
    }
}

private fun repositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        val settings = candidate.resolve("settings.gradle.kts")
        if (Files.isRegularFile(settings)) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
