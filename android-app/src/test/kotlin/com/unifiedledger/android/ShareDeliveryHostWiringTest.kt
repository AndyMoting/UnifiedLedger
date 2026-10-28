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

    @Test
    fun theCompositionRootBindsTheActivityAsTheInboundHostToken() {
        // P709SD-05: without this line the channel can never match a resumed token, every delivery
        // parks, and the whole suite would stay green — so pin the wiring itself.
        val root =
            repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/App.kt")
                .toFile()
                .readLines()
                .map { it.trim() }
                .filterNot { it.startsWith("//") }
        assertTrue(
            root.any { it == "inboundHostToken = activity," },
            "the Android composition root must bind the Activity as the inbound host token",
        )
    }

    @Test
    fun theCompositionBindsAndUnbindsTheSameHostToken() {
        // P709SD2-06: the actual bind point is the composition, one level below App.kt. Passing a null
        // token there would park every delivery silently while the rest of the suite stayed green.
        val composition =
            repositoryFile("app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt")
                .toFile()
                .readLines()
                .map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        assertTrue(
            composition.any { it.startsWith("externalNavigationChannel?.subscribe(inboundHostToken) { request ->") },
            "the composition must bind the inbound host token it was given",
        )
        assertTrue(
            composition.any { it.startsWith("onDispose { externalNavigationChannel?.subscribe(inboundHostToken, null) }") },
            "disposing the composition must unbind that same token",
        )
    }

    @Test
    fun theActivitySurfacesARefusedShareInsteadOfReturningSilently() {
        // ACC-D-SHARE-02: the refusal branch must actually report; a bare `return` here is the silent
        // drop, and no other assertion would notice it (the whole suite stays green without this).
        val activity =
            repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt")
                .toFile()
                .readText()
        assertTrue(
            activity.contains("rejectionFor(snapshot)?.let { rejection ->"),
            "the Activity must ask the intake for the typed refusal it just declined to deliver",
        )
        assertTrue(
            activity.contains("notifyShareRejection(rejection.toShareRejectionReason())"),
            "the refusal must reach the process-scoped channel, which shows it on the resumed host",
        )
        assertTrue(
            activity.contains("entry.inboundRejected kind="),
            "the refusal must be traceable in the startup trace",
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
