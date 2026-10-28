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
        // ACC-D-SHARE-02 turned the one-line onDispose into a block (it now unbinds BOTH the request
        // and the refusal binding), so this pins the two statements rather than the old one-liner.
        assertTrue(
            composition.any { it == "externalNavigationChannel?.subscribe(inboundHostToken, null)" },
            "disposing the composition must unbind the request binding of that same token",
        )
        assertTrue(
            composition.any { it == "externalNavigationChannel?.subscribeShareRejections(inboundHostToken, null)" },
            "disposing the composition must unbind the refusal binding too (otherwise a dead host keeps it)",
        )
    }

    @Test
    fun theCompositionRendersARefusalAsASiblingOfEverySurface() {
        // ACC-D-SHARE-02: a refusal has no pending slot to land in, and the reducer's Busy field exists
        // only on slot surfaces. Rendering it as a sibling of the surface content is what makes it
        // visible on the read-only detail / recycle bin / failure pages too, so pin both the binding and
        // the render seam's position.
        val composition =
            repositoryFile("app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt")
                .toFile()
                .readText()
        assertTrue(
            composition.contains("externalNavigationChannel?.subscribeShareRejections(inboundHostToken) { reason ->"),
            "the composition must bind the refusal channel to the same host token",
        )
        assertTrue(composition.contains("hostShareRejection = reason"), "the refusal must land the host-level notice")
        assertTrue(
            composition.contains("hostShareRejection?.let { reason ->"),
            "the notice must be rendered (a stored-but-never-shown value is the silent drop again)",
        )
        val noticeAt = composition.indexOf("hostShareRejection?.let { reason ->")
        val contentAt = composition.indexOf("Box(Modifier.weight(1f))")
        assertTrue(noticeAt in 0 until contentAt, "the notice must render ABOVE the surface content, not inside one state")
    }

    @Test
    fun theRefusalBranchRunsInsideTheNullRequestBranch() {
        // ACC-D-SHARE-02: a check that only greps for the notify call would still pass if the call had
        // drifted BELOW the branch's `return` (dead code). Bound the searched region by the accepted
        // path's `deliver(request)`, which comes after the branch.
        val activity =
            repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt")
                .toFile()
                .readText()
        val branchStart = activity.indexOf("if (request == null) {")
        assertTrue(branchStart >= 0, "the null branch must exist")
        // Bound at the branch's OWN `return` (a statement at this indentation), not at the accepted
        // path further down: a notify moved BELOW that return is dead code and must fail here.
        val branchReturn = activity.indexOf("            return", branchStart)
        assertTrue(branchReturn > branchStart, "the null branch must end with an indented return")
        val probeAt = activity.indexOf("rejectionFor(snapshot)?.let { rejection ->", branchStart)
        val notifyAt = activity.indexOf("notifyShareRejection(rejection.toShareRejectionReason())", branchStart)
        assertTrue(probeAt in (branchStart + 1) until branchReturn, "the refusal probe must run before the branch returns")
        assertTrue(notifyAt in (branchStart + 1) until branchReturn, "the refusal must notify before the branch returns")
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
