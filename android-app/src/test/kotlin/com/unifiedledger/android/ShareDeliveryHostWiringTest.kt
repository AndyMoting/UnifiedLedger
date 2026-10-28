package com.unifiedledger.android

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P7-09 09.A delivery order (spec section 2.2; ACC-D-SHARE-01). The Activity is the host the
 * process-scoped channel asks about, so its lifecycle must report resumed/paused; without that the
 * channel keeps delivering to a previous instance whose composition is still subscribed, and an
 * accepted share renders nowhere. The pair needs a real Activity to execute, so it is pinned
 * structurally on the source (the `AppShortcutsTest` / `AndroidRestoreWiringTest` precedent).
 */
class ShareDeliveryHostWiringTest {
    @Test
    fun theActivityReportsItsResumedStateToTheProcessScopedChannel() {
        val activity =
            repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt")
                .toFile()
                .readText()
        assertTrue(activity.contains("override fun onResume()"), "the host must report a resume")
        assertTrue(
            activity.contains("ledgerProcessHolderFor(this)?.externalNavigationChannel?.onHostResumed(this)"),
            "onResume must activate this instance on the process-scoped channel",
        )
        assertTrue(activity.contains("override fun onPause()"), "the host must report a pause")
        assertTrue(
            activity.contains("ledgerProcessHolderFor(this)?.externalNavigationChannel?.onHostPaused(this)"),
            "onPause must deactivate this instance so a paused host can never take a request",
        )
    }

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}
